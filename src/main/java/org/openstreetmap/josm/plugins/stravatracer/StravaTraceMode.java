// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.DoubleProperty;
import org.openstreetmap.josm.data.preferences.IntegerProperty;
import org.openstreetmap.josm.data.preferences.StringProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Map mode: click on a trace of the Strava heatmap to create the corresponding way.
 */
public class StravaTraceMode extends MapMode {

    /** Absolute minimum intensity (0-255) of a trace pixel. */
    public static final IntegerProperty THRESHOLD = new IntegerProperty("stravatracer.threshold", 10);
    /** Minimum intensity of a trace pixel, relative to the hottest pixel near the click (0-1). */
    public static final DoubleProperty RELATIVE = new DoubleProperty("stravatracer.relative", 0.2);
    /** Distance (in meters) around the existing ways where the heatmap is ignored. */
    public static final DoubleProperty BUFFER = new DoubleProperty("stravatracer.buffer", 15);
    /** Maximum distance (in meters) between a free end of a trace and the way it is connected to. */
    public static final DoubleProperty SNAP = new DoubleProperty("stravatracer.snap", 40);
    /** Maximum zoom level of the heatmap tiles. */
    public static final IntegerProperty MAX_ZOOM = new IntegerProperty("stravatracer.zoom", 16);
    /** Number of tiles downloaded on each side of the clicked tile. */
    public static final IntegerProperty RADIUS = new IntegerProperty("stravatracer.radius", 1);
    /** Maximum number of tiles on each side of the clicked tile, when the trace reaches the border. */
    public static final IntegerProperty MAX_RADIUS = new IntegerProperty("stravatracer.maxradius", 3);
    /** Also create the traces branching from the clicked one. */
    public static final BooleanProperty BRANCHES = new BooleanProperty("stravatracer.branches", false);
    /** Douglas-Peucker tolerance (in pixels). */
    public static final DoubleProperty SIMPLIFY = new DoubleProperty("stravatracer.simplify", 1.0);
    /** Tags of the new ways, as {@code key=value;key=value}. */
    public static final StringProperty TAGS = new StringProperty("stravatracer.tags", "highway=path");
    /** Writes the diagnostic files after each click, not only when no trace is found. */
    public static final BooleanProperty DEBUG = new BooleanProperty("stravatracer.debug", false);

    private final AtomicBoolean running = new AtomicBoolean();

    public StravaTraceMode() {
        super(tr("Strava trace"), "stravatrace",
                tr("Click on a Strava heatmap trace to create the missing way"),
                Shortcut.registerShortcut("mapmode:stravatrace", tr("Mode: {0}", tr("Strava trace")),
                        KeyEvent.CHAR_UNDEFINED, Shortcut.NONE),
                Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
    }

    @Override
    public void enterMode() {
        super.enterMode();
        MainApplication.getMap().mapView.addMouseListener(this);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        MainApplication.getMap().mapView.removeMouseListener(this);
    }

    @Override
    public boolean layerIsSupported(Layer l) {
        return isEditableDataLayer(l);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null || ds.isLocked()) {
            notify(tr("No editable data layer"));
            return;
        }
        AbstractTileSourceLayer<?> strava = findStravaLayer();
        if (strava == null) {
            notify(tr("No visible Strava heatmap layer. Add one from the Imagery menu."));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        LatLon click = MainApplication.getMap().mapView.getLatLon(e.getX(), e.getY());
        ImageryInfo info = strava.getInfo();
        MainApplication.getMap().mapView.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        MainApplication.worker.submit(() -> {
            try {
                trace(ds, info, click);
            } catch (IOException ex) {
                Logging.warn(ex);
                GuiHelper.runInEDT(() -> notify(ex.getMessage()));
            } catch (RuntimeException ex) {
                Logging.error(ex);
                GuiHelper.runInEDT(() -> notify(tr("Strava tracing failed: {0}", ex)));
            } finally {
                running.set(false);
                GuiHelper.runInEDT(() -> MainApplication.getMap().mapView.setCursor(cursor));
            }
        });
    }

    /** Runs in the worker thread. */
    private void trace(DataSet ds, ImageryInfo info, LatLon click) throws IOException {
        int zoom = MAX_ZOOM.get();
        if (info.getMaxZoom() > 0) {
            zoom = Math.min(zoom, info.getMaxZoom());
        }
        TraceExtractor.Params params = new TraceExtractor.Params();
        params.threshold = THRESHOLD.get();
        params.relativeThreshold = RELATIVE.get();
        params.simplifyTolerance = SIMPLIFY.get();
        params.branches = BRANCHES.get();

        // Enlarge the window while the trace reaches its border
        HeatmapWindow window;
        List<Way> ways;
        boolean[][] mask;
        List<List<Point>> lines;
        int x;
        int y;
        int radius = RADIUS.get();
        while (true) {
            window = HeatmapWindow.load(info, zoom, click, radius, DEBUG.get());
            ds.getReadLock().lock();
            try {
                LatLon ul = window.toLatLon(0, 0);
                LatLon lr = window.toLatLon(window.getWidth(), window.getHeight());
                ways = new ArrayList<>(ds.searchWays(new BBox(ul, lr)));
                mask = TraceBuilder.mask(ways, window, BUFFER.get());
            } finally {
                ds.getReadLock().unlock();
            }
            Point2D.Double p = window.toPixel(click);
            x = (int) Math.floor(p.x);
            y = (int) Math.floor(p.y);
            lines = TraceExtractor.extract(window.getIntensity(), mask, x, y, params);
            if (radius >= MAX_RADIUS.get() || !reachesBorder(lines, window)) {
                break;
            }
            radius++;
            Logging.info("Strava tracer: trace reaches the border, enlarging the window to radius " + radius);
        }
        if (lines.isEmpty() || DEBUG.get()) {
            String where = "";
            try {
                where = Diagnostics.dump(window, mask, x, y, params.effectiveThreshold).getPath();
            } catch (IOException ex) {
                Logging.warn(ex);
            }
            if (lines.isEmpty()) {
                String dir = where;
                GuiHelper.runInEDT(() -> notify(tr("No trace found here (threshold {0}, or already mapped).",
                        params.threshold) + (dir.isEmpty() ? "" : "<br>" + tr("Diagnostics: {0}", dir))));
                return;
            }
        }

        HeatmapWindow finalWindow = window;
        List<Way> finalWays = ways;
        List<List<Point>> finalLines = lines;
        GuiHelper.runInEDT(() -> {
            List<Way> created = new ArrayList<>();
            Command cmd = TraceBuilder.build(ds, finalWays, finalLines, finalWindow, SNAP.get(),
                    parseTags(TAGS.get()), created);
            if (cmd != null) {
                UndoRedoHandler.getInstance().add(cmd);
                ds.setSelected(created);
            }
        });
    }

    /** Tells if a trace ends near the border of the window, and so is probably cut. */
    static boolean reachesBorder(List<List<Point>> lines, HeatmapWindow window) {
        int margin = 4;
        for (List<Point> line : lines) {
            for (Point p : List.of(line.get(0), line.get(line.size() - 1))) {
                if (p.x < margin || p.y < margin || p.x >= window.getWidth() - margin
                        || p.y >= window.getHeight() - margin) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AbstractTileSourceLayer<?> findStravaLayer() {
        // The layer list is ordered from top to bottom
        for (AbstractTileSourceLayer<?> layer : MainApplication.getLayerManager()
                .getLayersOfType(AbstractTileSourceLayer.class)) {
            String url = layer.getInfo().getUrl();
            if (layer.isVisible() && (layer.getName().toLowerCase(Locale.ROOT).contains("strava")
                    || (url != null && url.toLowerCase(Locale.ROOT).contains("strava")))) {
                return layer;
            }
        }
        return null;
    }

    static Map<String, String> parseTags(String text) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (String pair : text.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                tags.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return tags;
    }

    private static void notify(String message) {
        new Notification(message).setIcon(JOptionPane.WARNING_MESSAGE).show();
    }
}
