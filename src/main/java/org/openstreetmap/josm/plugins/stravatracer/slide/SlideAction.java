// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.actions.downloadtasks.DownloadOsmTask;
import org.openstreetmap.josm.actions.downloadtasks.DownloadParams;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.DownloadPolicy;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.preferences.DoubleProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.PleaseWaitRunnable;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.NetworkManager;
import org.openstreetmap.josm.io.OnlineResource;
import org.openstreetmap.josm.plugins.stravatracer.StravaLayers;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Slides the selected line, or the selected section of a line, along the Strava heatmap
 * (port of the Slide operation of strava-heatmap-extension).
 */
public class SlideAction extends JosmAction {

    private static final long serialVersionUID = 1L;

    /** Download the data along the line before sliding it, where it was not downloaded yet. */
    public static final BooleanProperty DOWNLOAD = new BooleanProperty("stravatracer.slide.download", false);
    /** Half width (m) of the band downloaded along the line. */
    public static final DoubleProperty DOWNLOAD_DISTANCE = new DoubleProperty("stravatracer.slide.download.distance",
            50);
    /** Most download requests made along one line. */
    private static final int MAX_DOWNLOADS = 40;

    private volatile boolean busy;

    public SlideAction() {
        super(tr("Slide along Strava heatmap"), "slide",
                tr("Slide the selected line, or the section between two selected points, along the Strava heatmap"),
                Shortcut.registerShortcut("tools:stravaslide", tr("Tools: {0}", tr("Slide along Strava heatmap")),
                        KeyEvent.VK_S, Shortcut.SHIFT),
                true);
    }

    /** The selected way and range, as read from the data set. */
    static final class Target {
        final Way way;
        final int start;
        final int end;
        final List<Node> nodes;

        Target(Way way, int start, int end) {
            this.way = way;
            this.start = start;
            this.end = end;
            this.nodes = new ArrayList<>(way.getNodes().subList(start, end + 1));
        }
    }

    /**
     * Resolves the selection: one way (the whole line), or two or more nodes all on exactly one common way
     * (the range between the first and the last of them). Port of getSlideSelection().
     * @param selection selected primitives
     * @return the target, or {@code null}
     */
    static Target target(Collection<? extends OsmPrimitive> selection) {
        if (selection.isEmpty()) {
            return null;
        }
        Way way;
        int start;
        int end;
        if (selection.size() == 1 && selection.iterator().next() instanceof Way) {
            way = (Way) selection.iterator().next();
            start = 0;
            end = way.getNodesCount() - 1;
        } else if (selection.size() >= 2 && selection.stream().allMatch(p -> p instanceof Node)) {
            List<Way> common = new ArrayList<>();
            Node first = (Node) selection.iterator().next();
            for (Way parent : first.getParentWays()) {
                if (selection.stream().allMatch(p -> parent.containsNode((Node) p))) {
                    common.add(parent);
                }
            }
            if (common.size() != 1) {
                return null;
            }
            way = common.get(0);
            start = Integer.MAX_VALUE;
            end = -1;
            for (OsmPrimitive p : selection) {
                int index = way.getNodes().indexOf(p);
                start = Math.min(start, index);
                end = Math.max(end, index);
            }
        } else {
            return null;
        }
        if (!way.isUsable() || way.getNodesCount() < 2) {
            return null;
        }
        // Closed lines (a loop, not an area) are allowed; any other repeated node is ambiguous.
        List<Node> distinct = way.isClosed() ? way.getNodes().subList(0, way.getNodesCount() - 1) : way.getNodes();
        if (start == end || (way.isClosed() && way.isArea()) || new HashSet<>(distinct).size() != distinct.size()) {
            return null;
        }
        return new Target(way, start, end);
    }

    // Bridges and tunnels are mapped to the structure, not to the heat.
    static boolean isStructure(Way way) {
        for (String key : new String[] {"bridge", "tunnel"}) {
            String value = way.get(key);
            if (value != null && !"no".equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** Tells if a way is a road or path, whose heat is its own. */
    public static boolean isHighway(Way way) {
        return way.hasKey("highway");
    }

    /** Returns the [lon, lat] vertices of a way. */
    public static List<double[]> geometry(Way way) {
        List<double[]> locs = new ArrayList<>();
        for (Node n : way.getNodes()) {
            if (n.isLatLonKnown()) {
                locs.add(new double[] {n.lon(), n.lat()});
            }
        }
        return locs;
    }

    /** OSM context of the selected range. */
    static SlideCleanup.Selection selection(Target target) {
        List<SlideCleanup.Node> nodes = new ArrayList<>();
        for (Node n : target.nodes) {
            List<Way> parents = n.getParentWays();
            List<List<double[]>> others = new ArrayList<>();
            for (Way other : parents) {
                if (other != target.way && isHighway(other)) {
                    others.add(geometry(other));
                }
            }
            boolean relation = n.referrers(Relation.class).findAny().isPresent();
            nodes.add(new SlideCleanup.Node(new double[] {n.lon(), n.lat()}, n.hasKeys(), parents.size(), relation,
                    others));
        }
        return new SlideCleanup.Selection(nodes, target.start, target.end, target.way.getNodesCount());
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        start(true);
    }

    private void start(boolean download) {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null || ds.isLocked() || busy) {
            return;
        }
        Target target = target(ds.getSelected());
        if (target == null) {
            notify(tr("Select a line, or two or more points on the same line."));
            return;
        }
        if (isStructure(target.way)) {
            notify(tr("Slide does not move bridges or tunnels."));
            return;
        }
        if (target.nodes.size() > 1000) {
            notify(tr("Select a shorter section (at most 1000 points)."));
            return;
        }
        if (target.nodes.stream().anyMatch(n -> !n.isLatLonKnown())) {
            notify(tr("Some points of the line are incomplete. Download the data around the line first."));
            return;
        }
        AbstractTileSourceLayer<?> layer = StravaLayers.find();
        if (layer == null) {
            notify(tr("No visible Strava heatmap layer. Add one from the Imagery menu."));
            return;
        }
        if (layer.getDisplaySettings().getDisplacement().east() != 0
                || layer.getDisplaySettings().getDisplacement().north() != 0) {
            notify(tr("Reset the offset of the Strava heatmap layer before sliding."));
            return;
        }
        if (download && DOWNLOAD.get() && downloadAlong(ds, target)) {
            return;
        }
        busy = true;
        MainApplication.worker.submit(new SlideTask(ds, target, layer.getInfo()));
    }

    /**
     * Downloads the parts of the band along the line that are not in the data set yet, then slides the
     * selection again.
     * @return {@code true} if downloads were started (Slide follows them), {@code false} if there is
     *         nothing to download (or it cannot be)
     */
    private boolean downloadAlong(DataSet ds, Target target) {
        if (ds.getDownloadPolicy() == DownloadPolicy.BLOCKED || NetworkManager.isOffline(OnlineResource.OSM_API)) {
            return false;
        }
        List<LatLon> line = new ArrayList<>();
        for (Node n : target.nodes) {
            line.add(n.getCoor());
        }
        List<Bounds> missing = DownloadAlong.missing(ds, line, DOWNLOAD_DISTANCE.get());
        if (missing.isEmpty()) {
            return false;
        }
        if (missing.size() > MAX_DOWNLOADS) {
            notify(tr("The band along the line is too large to download ({0} requests). "
                    + "Sliding with the data already downloaded.", missing.size()));
            return false;
        }
        busy = true;
        for (Bounds bounds : missing) {
            new DownloadOsmTask().download(new DownloadParams(), bounds, null);
        }
        // The downloads run in the worker thread: Slide starts once they are merged into the layer
        MainApplication.worker.submit(() -> GuiHelper.runInEDT(() -> {
            busy = false;
            start(false);
        }));
        return true;
    }

    /** Background part: tiles, raster and fit; then the edit in the EDT. */
    private final class SlideTask extends PleaseWaitRunnable {
        private final DataSet ds;
        private final Target target;
        private final ImageryInfo info;
        private final List<Node> wayNodesBefore;
        private final List<LatLon> coordinatesBefore = new ArrayList<>();
        private volatile boolean cancelled;
        private Result result;
        private Exception error;
        // Inputs of the run, for the diagnostics
        private SlideCleanup.Selection selection;
        private List<SlideAlgorithm.Tile> tiles;
        private final List<SlideDebug.OtherWay> debugWays = new ArrayList<>();
        private final List<List<Integer>> debugNodeOthers = new ArrayList<>();
        private int sectionCount = 1;
        /** Index of the first vertex of the section being slid. */
        private int sectionStart;

        SlideTask(DataSet ds, Target target, ImageryInfo info) {
            super(tr("Slide along Strava heatmap"), false);
            this.ds = ds;
            this.target = target;
            this.info = info;
            this.wayNodesBefore = target.way.getNodes();
            for (Node n : target.nodes) {
                coordinatesBefore.add(n.getCoor());
            }
        }

        @Override
        protected void cancel() {
            cancelled = true;
        }

        @Override
        protected void realRun() {
            try {
                List<double[]> locations = new ArrayList<>();
                List<List<double[]>> others = new ArrayList<>();
                ds.getReadLock().lock();
                try {
                    selection = selection(target);
                    for (SlideCleanup.Node n : selection.nodes) {
                        locations.add(n.loc);
                    }
                    // Other roads and paths near the line: their heat is theirs.
                    double minLon = Double.POSITIVE_INFINITY;
                    double minLat = Double.POSITIVE_INFINITY;
                    double maxLon = Double.NEGATIVE_INFINITY;
                    double maxLat = Double.NEGATIVE_INFINITY;
                    for (double[] loc : locations) {
                        minLon = Math.min(minLon, loc[0]);
                        maxLon = Math.max(maxLon, loc[0]);
                        minLat = Math.min(minLat, loc[1]);
                        maxLat = Math.max(maxLat, loc[1]);
                    }
                    double pad = SlideAlgorithm.MAX_SEARCH_RADIUS_METERS / 111320;
                    double padLon = pad / Math.cos(Math.toRadians((minLat + maxLat) / 2));
                    BBox box = new BBox(minLon - padLon, minLat - pad, maxLon + padLon, maxLat + pad);
                    List<Way> otherWays = new ArrayList<>();
                    for (Way other : ds.searchWays(box)) {
                        if (other != target.way && other.isUsable() && isHighway(other)) {
                            others.add(geometry(other));
                            otherWays.add(other);
                        }
                    }
                    // For the diagnostics: the ways connected to the line, highways or not
                    for (Node n : target.nodes) {
                        List<Integer> indices = new ArrayList<>();
                        for (Way parent : n.getParentWays()) {
                            if (parent == target.way) {
                                continue;
                            }
                            if (!otherWays.contains(parent)) {
                                otherWays.add(parent);
                            }
                            indices.add(otherWays.indexOf(parent));
                        }
                        debugNodeOthers.add(indices);
                    }
                    for (Way other : otherWays) {
                        debugWays.add(new SlideDebug.OtherWay(isHighway(other), geometry(other)));
                    }
                } finally {
                    ds.getReadLock().unlock();
                }
                getProgressMonitor().setTicksCount(1000);
                SlideCleanup.Selection whole = selection;
                Set<Integer> fixed = new LinkedHashSet<>();
                fixed.add(0);
                fixed.add(locations.size() - 1);
                fixed.addAll(SlideCleanup.fixedIndices(whole));
                // A line too long for one raster is slid section by section
                List<int[]> sections = SlideTiles.sections(locations, fixed);
                sectionCount = sections.size();
                Result merged = new Result();
                for (int s = 0; s < sectionCount; s++) {
                    int first = sections.get(s)[0];
                    int last = sections.get(s)[1];
                    sectionStart = first;
                    selection = sectionCount == 1 ? whole : new SlideCleanup.Selection(
                            whole.nodes.subList(first, last + 1), whole.start + first, whole.start + last,
                            whole.wayNodeCount);
                    Result part = slide(locations.subList(first, last + 1), others, s);
                    for (Record r : part) {
                        if (r.nodeIndex != null) {
                            r.nodeIndex += first;
                        }
                    }
                    // The first point of a section is the last one of the previous section
                    merged.addAll(s == 0 ? part : part.subList(1, part.size()));
                    merged.skipped += part.skipped;
                    merged.alongOther += part.alongOther;
                }
                selection = whole;
                sectionStart = 0;
                result = merged;
            } catch (SlideAlgorithm.CancelledException e) {
                cancelled = true;
            } catch (SlideAlgorithm.NoSignalException | IOException | IllegalArgumentException e) {
                error = e;
            } catch (RuntimeException e) {
                Logging.error(e);
                error = e;
                dump(e.toString());
            }
        }

        /** Slides one section of the line (the whole line when it fits in one raster). */
        private Result slide(List<double[]> locations, List<List<double[]>> others, int section)
                throws IOException {
            int base = 1000 * section / sectionCount;
            int share = 1000 / sectionCount;
            getProgressMonitor().subTask(sectionCount == 1 ? tr("Loading heatmap…")
                    : tr("Section {0} of {1}: loading heatmap…", section + 1, sectionCount));
            SlideTiles.Loaded loaded = SlideTiles.load(info, locations, () -> cancelled,
                    v -> getProgressMonitor().setTicks(base + (int) (v * 0.35 * share)));
            getProgressMonitor().subTask(sectionCount == 1 ? tr("Following heatmap density…")
                    : tr("Section {0} of {1}: following heatmap density…", section + 1, sectionCount));
            tiles = loaded.tiles;
            SlideRaster raster = SlideAlgorithm.createHeatmapRaster(loaded.tiles, SlideTiles.SLIDE_ZOOM,
                    loaded.bounds);
            SlideAlgorithm.Options options = new SlideAlgorithm.Options();
            // The line's first and last points stay where they were drawn too.
            options.fixedIndices.add(0);
            options.fixedIndices.add(locations.size() - 1);
            options.fixedIndices.addAll(SlideCleanup.fixedIndices(selection));
            options.otherWays = others;
            options.cancelled = () -> cancelled;
            options.onProgress = v -> getProgressMonitor().setTicks(base + (int) ((0.35 + v * 0.65) * share));
            Result slid = SlideAlgorithm.slideLineTwice(locations, raster, options);
            Result cleaned = SlideCleanup.cleanSlideResult(selection, slid);
            cleaned.skipped = slid.skipped;
            return cleaned;
        }

        /** Writes the run as a test case, and returns a sentence telling where, or an empty string. */
        private String dump(String message) {
            // The tiles kept are those of the current section: a merged result of several sections is not dumped
            if (selection == null || tiles == null || (sectionCount > 1 && selection.nodes.size() == target.nodes.size())) {
                return "";
            }
            try {
                List<List<Integer>> nodeOthers = debugNodeOthers.subList(sectionStart,
                        sectionStart + selection.nodes.size());
                return "<br>" + tr("Diagnostics: {0}", SlideDebug.write(message, tiles, selection, nodeOthers,
                        debugWays, sectionCount > 1 ? null : result).getPath());
            } catch (IOException | RuntimeException e) {
                Logging.warn(e);
                return "";
            }
        }

        @Override
        protected void finish() {
            busy = false;
            if (cancelled) {
                return;
            }
            if (error != null) {
                SlideAction.notify(error.getMessage() != null ? error.getMessage()
                        : tr("Slide failed. No edits were applied."));
                return;
            }
            GuiHelper.runInEDT(this::apply);
        }

        private boolean unchanged() {
            if (!Objects.equals(target.way.getNodes(), wayNodesBefore) || target.way.isDeleted()) {
                return false;
            }
            for (int i = 0; i < target.nodes.size(); i++) {
                Node n = target.nodes.get(i);
                if (n.isDeleted() || !Objects.equals(n.getCoor(), coordinatesBefore.get(i))) {
                    return false;
                }
            }
            return true;
        }

        private void apply() {
            if (!unchanged()) {
                SlideAction.notify(tr("The line changed while Slide was running. Please try again."));
                return;
            }
            try {
                Command command = createCommand(ds, target, selection(target), result);
                if (command != null) {
                    UndoRedoHandler.getInstance().add(command);
                }
            } catch (IllegalStateException e) {
                SlideAction.notify(e.getMessage() + dump(e.getMessage()));
                return;
            }
            List<String> messages = new ArrayList<>();
            if (result.skipped > 0) {
                messages.add(trn("{0} section without a usable heatmap trail left as drawn.",
                        "{0} sections without a usable heatmap trail left as drawn.", result.skipped, result.skipped));
            }
            if (result.alongOther > 0) {
                messages.add(trn("{0} section following a connected way''s trail left as drawn.",
                        "{0} sections following a connected way''s trail left as drawn.", result.alongOther,
                        result.alongOther));
            }
            if (!messages.isEmpty()) {
                new Notification(String.join("<br>", messages)).setIcon(JOptionPane.INFORMATION_MESSAGE)
                        .setDuration(Notification.TIME_LONG).show();
            }
        }
    }

    /**
     * Builds the edit: moves the retained original nodes, creates nodes for the points Slide created,
     * rewrites the way's node list and deletes the original nodes that cleanup dropped.
     * Port of createSlideAction().
     * @throws IllegalStateException if the result is invalid (no edit is applied)
     */
    static Command createCommand(DataSet ds, Target target, SlideCleanup.Selection selection, List<Record> points) {
        Set<Integer> protectedNodes = new HashSet<>(SlideCleanup.protectedIndices(selection));
        protectedNodes.add(0);
        protectedNodes.add(selection.nodes.size() - 1);
        List<Integer> keptIndexes = new ArrayList<>();
        for (Record point : points) {
            if (point.nodeIndex != null) {
                keptIndexes.add(point.nodeIndex);
            }
            if (point.loc.length != 2 || !Double.isFinite(point.loc[0]) || !Double.isFinite(point.loc[1])) {
                throw invalid(tr("invalid coordinates"));
            }
        }
        Set<Integer> kept = new HashSet<>(keptIndexes);
        if (keptIndexes.isEmpty() || keptIndexes.get(0) != 0) {
            throw invalid(tr("it does not start at the first point"));
        }
        if (keptIndexes.get(keptIndexes.size() - 1) != selection.nodes.size() - 1) {
            throw invalid(tr("it does not end at the last point"));
        }
        for (int i = 1; i < keptIndexes.size(); i++) {
            if (keptIndexes.get(i) <= keptIndexes.get(i - 1)) {
                throw invalid(tr("point {0} comes after point {1}", keptIndexes.get(i), keptIndexes.get(i - 1)));
            }
        }
        for (int index : protectedNodes) {
            if (!kept.contains(index)) {
                throw invalid(tr("protected point {0} ({1}) was dropped", index, target.nodes.get(index).getUniqueId()));
            }
        }
        int last = target.nodes.size() - 1;
        if (target.nodes.get(0) == target.nodes.get(last)
                && (points.get(0).loc[0] != points.get(points.size() - 1).loc[0]
                    || points.get(0).loc[1] != points.get(points.size() - 1).loc[1])) {
            throw new IllegalStateException(tr("Invalid Slide result for a closed line. No edits were applied."));
        }
        Set<Integer> fixed = SlideCleanup.fixedIndices(selection);
        for (Record point : points) {
            if (point.nodeIndex != null && fixed.contains(point.nodeIndex)) {
                double[] loc = selection.nodes.get(point.nodeIndex).loc;
                if (point.loc[0] != loc[0] || point.loc[1] != loc[1]) {
                    throw new IllegalStateException(tr("Slide attempted to move a protected node. No edits were applied."));
                }
            }
        }
        int count = target.way.getNodesCount() - target.nodes.size() + points.size();
        if (count > 2000) {
            throw new IllegalStateException(tr("Result exceeds the OSM way node limit. Slide a shorter section."));
        }
        List<Command> commands = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        Set<Node> moved = new HashSet<>();
        for (Record point : points) {
            LatLon ll = new LatLon(point.loc[1], point.loc[0]);
            if (point.nodeIndex == null) {
                Node node = new Node(ll);
                commands.add(new AddCommand(ds, node));
                nodes.add(node);
            } else {
                Node node = target.nodes.get(point.nodeIndex);
                // A closed line lists its closing node twice: move it once
                if (!node.getCoor().equals(ll) && moved.add(node)) {
                    commands.add(new MoveCommand(node, ll));
                }
                nodes.add(node);
            }
        }
        // Splice the nodes into the way: everything outside the slid range keeps its original neighbours
        List<Node> wayNodes = new ArrayList<>(target.way.getNodes().subList(0, target.start));
        wayNodes.addAll(nodes);
        wayNodes.addAll(target.way.getNodes().subList(target.end + 1, target.way.getNodesCount()));
        commands.add(new ChangeNodesCommand(ds, target.way, wayNodes));
        // Originals that cleanup dropped are deleted, unless something else uses them
        List<Node> dropped = new ArrayList<>();
        Set<Node> stillUsed = new HashSet<>(wayNodes);
        for (int index = 0; index < target.nodes.size(); index++) {
            Node node = target.nodes.get(index);
            if (kept.contains(index) || stillUsed.contains(node) || dropped.contains(node)) {
                continue;
            }
            if (node.hasKeys() || node.getParentWays().size() > 1 || node.referrers(Relation.class).findAny().isPresent()) {
                throw new IllegalStateException(tr("Slide cleanup encountered a protected connection. No edits were applied."));
            }
            dropped.add(node);
        }
        if (!dropped.isEmpty()) {
            commands.add(new DeleteCommand(ds, dropped));
        }
        return new SequenceCommand(tr("Slide along Strava heatmap"), commands);
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException(tr("Invalid Slide result ({0}). No edits were applied.", reason));
    }

    @Override
    protected void updateEnabledState() {
        updateEnabledStateOnCurrentSelection();
    }

    @Override
    protected void updateEnabledState(Collection<? extends OsmPrimitive> selection) {
        DataSet ds = getLayerManager().getEditDataSet();
        setEnabled(ds != null && !ds.isLocked() && target(selection) != null);
    }

    private static void notify(String message) {
        new Notification(message).setIcon(JOptionPane.WARNING_MESSAGE).show();
    }
}
