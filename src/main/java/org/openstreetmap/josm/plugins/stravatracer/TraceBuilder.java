// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Masks the existing ways on the heatmap, and turns the extracted traces into OSM ways
 * connected to the existing ones.
 */
public final class TraceBuilder {

    /** A connection whose heat drops below this share of the heat at the end of the trace crosses dark ground. */
    static final double DARK_SHARE = 0.1;

    private TraceBuilder() {
    }

    /**
     * Tells if a way is a linear feature that may be visible on the heatmap.
     * @param w way
     * @return {@code true} if the way must be masked and can be used for snapping
     */
    static boolean isTraceable(Way w) {
        return w.isUsable() && w.getNodesCount() >= 2
                && (w.hasKey("highway") || w.hasKey("railway") || w.hasKey("area:highway")
                    || w.hasTag("leisure", "track") || w.hasTag("route", "ferry")
                    || w.hasTag("aeroway", "runway", "taxiway"));
    }

    private static boolean isArea(Way w) {
        return w.isClosed() && (w.hasTag("area", "yes") || w.hasKey("area:highway"));
    }

    /**
     * Draws the existing ways on a mask of the window.
     * @param ways candidate ways
     * @param window heatmap window
     * @param bufferMeters distance around the ways to mask
     * @return {@code true} for the masked pixels, indexed as {@code [y][x]}
     */
    public static boolean[][] mask(Collection<Way> ways, HeatmapWindow window, double bufferMeters) {
        int w = window.getWidth();
        int h = window.getHeight();
        LatLon center = window.toLatLon(w / 2.0, h / 2.0);
        float width = (float) (2 * bufferMeters / window.metersPerPixel(center.lat()) + 1);

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Way way : ways) {
            if (!isTraceable(way)) {
                continue;
            }
            Path2D.Double path = new Path2D.Double();
            boolean first = true;
            for (Node node : way.getNodes()) {
                if (!node.isLatLonKnown()) {
                    continue;
                }
                Point2D.Double p = window.toPixel(node.getCoor());
                if (first) {
                    path.moveTo(p.x, p.y);
                    first = false;
                } else {
                    path.lineTo(p.x, p.y);
                }
            }
            if (isArea(way)) {
                g.fill(path);
            }
            g.draw(path);
        }
        g.dispose();

        boolean[][] mask = new boolean[h][w];
        Raster raster = image.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                mask[y][x] = raster.getSample(x, y, 0) != 0;
            }
        }
        return mask;
    }

    /**
     * Builds the command creating the traced ways.
     * @param ds target data set
     * @param ways existing ways around the window
     * @param lines extracted polylines (window pixels)
     * @param window heatmap window
     * @param snapMeters maximum distance between a free end of a trace and an existing way
     * @param tags tags of the new ways
     * @param created receives the created ways
     * @return the command, or {@code null} if there is nothing to create
     */
    public static Command build(DataSet ds, Collection<Way> ways, List<List<Point>> lines, HeatmapWindow window,
            double snapMeters, Map<String, String> tags, List<Way> created) {
        return buildGeographic(ds, ways, toLatLon(lines, window), window, snapMeters, tags, created);
    }

    /**
     * Converts the extracted polylines from window pixels to coordinates. A junction shared by several
     * lines is the same point in all of them, and gets equal coordinates.
     * @param lines extracted polylines (window pixels)
     * @param window heatmap window
     * @return the polylines
     */
    public static List<List<LatLon>> toLatLon(List<List<Point>> lines, HeatmapWindow window) {
        List<List<LatLon>> result = new ArrayList<>();
        for (List<Point> line : lines) {
            List<LatLon> coordinates = new ArrayList<>();
            for (Point p : line) {
                coordinates.add(window.toLatLon(p.x, p.y));
            }
            result.add(coordinates);
        }
        return result;
    }

    /**
     * Builds the command creating the traced ways, from polylines in coordinates (after Slide).
     * Points with equal coordinates in several lines are junctions.
     * @param ds target data set
     * @param ways existing ways around the window
     * @param lines polylines
     * @param window heatmap window
     * @param snapMeters maximum distance between a free end of a trace and an existing way
     * @param tags tags of the new ways
     * @param created receives the created ways
     * @return the command, or {@code null} if there is nothing to create
     */
    public static Command buildGeographic(DataSet ds, Collection<Way> ways, List<List<LatLon>> lines,
            HeatmapWindow window, double snapMeters, Map<String, String> tags, List<Way> created) {
        List<Command> commands = new ArrayList<>();
        // A junction is the end of a line, and either the end or an inner point of the other lines
        Map<LatLon, Node> sharedNodes = new HashMap<>();
        Map<LatLon, Integer> occurrences = new HashMap<>();
        for (List<LatLon> line : lines) {
            sharedNodes.put(line.get(0), null);
            sharedNodes.put(line.get(line.size() - 1), null);
        }
        for (List<LatLon> line : lines) {
            for (LatLon p : new HashSet<>(line)) {
                if (sharedNodes.containsKey(p)) {
                    occurrences.merge(p, 1, Integer::sum);
                }
            }
        }

        // Existing ways modified by the snapping: way -> new list of nodes
        Map<Way, List<Node>> modified = new LinkedHashMap<>();
        List<Way> candidates = new ArrayList<>();
        for (Way way : ways) {
            if (isTraceable(way) && !isArea(way)) {
                candidates.add(way);
            }
        }

        for (List<LatLon> line : lines) {
            if (line.size() < 2) {
                continue;
            }
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < line.size(); i++) {
                LatLon p = line.get(i);
                boolean shared = sharedNodes.containsKey(p);
                Node node = shared ? sharedNodes.get(p) : null;
                if (node == null) {
                    node = new Node(p);
                    commands.add(new AddCommand(ds, node));
                    if (shared) {
                        sharedNodes.put(p, node);
                    }
                }
                nodes.add(node);
            }
            // Connect the free ends to the nearest existing way
            if (occurrences.get(line.get(0)) == 1) {
                Node snap = snap(ds, nodes.get(0), candidates, modified, window, snapMeters, commands);
                if (snap != null) {
                    nodes.add(0, snap);
                }
            }
            if (occurrences.get(line.get(line.size() - 1)) == 1) {
                Node snap = snap(ds, nodes.get(nodes.size() - 1), candidates, modified, window, snapMeters, commands);
                if (snap != null) {
                    nodes.add(snap);
                }
            }
            Way way = new Way();
            way.setNodes(nodes);
            way.setKeys(tags);
            commands.add(new AddCommand(ds, way));
            created.add(way);
        }
        for (Map.Entry<Way, List<Node>> entry : modified.entrySet()) {
            commands.add(new ChangeNodesCommand(ds, entry.getKey(), entry.getValue()));
        }
        if (created.isEmpty()) {
            return null;
        }
        return new SequenceCommand(tr("Trace {0} way(s) from the Strava heatmap", created.size()), commands);
    }

    /**
     * Finds the point of the existing ways nearest to a free end, and returns the node to connect to:
     * an existing node if it is close enough, otherwise a new node inserted in the way.
     */
    private static Node snap(DataSet ds, Node end, List<Way> candidates, Map<Way, List<Node>> modified,
            HeatmapWindow window, double snapMeters, List<Command> commands) {
        double metersPerPixel = window.metersPerPixel(end.lat());
        Point2D.Double p = window.toPixel(end.getCoor());
        // The nearest point, unless the line to it crosses dark ground and another one is reached over the heat
        int endHeat = heatAround(window, p);
        double best = snapMeters / metersPerPixel;
        double bestOnHeat = best;
        Way bestWay = null;
        int bestIndex = -1;
        Point2D.Double bestPoint = null;
        boolean bestDark = false;
        Way heatWay = null;
        int heatIndex = -1;
        Point2D.Double heatPoint = null;
        for (Way way : candidates) {
            List<Node> nodes = modified.getOrDefault(way, way.getNodes());
            for (int i = 0; i < nodes.size() - 1; i++) {
                Point2D.Double a = window.toPixel(nodes.get(i).getCoor());
                Point2D.Double b = window.toPixel(nodes.get(i + 1).getCoor());
                Point2D.Double q = nearestOnSegment(p, a, b);
                double d = p.distance(q);
                if (d >= best && d >= bestOnHeat) {
                    continue;
                }
                boolean dark = endHeat > 0 && minHeat(window, p, q) < DARK_SHARE * endHeat;
                if (d < best) {
                    best = d;
                    bestWay = way;
                    bestIndex = i;
                    bestPoint = q;
                    bestDark = dark;
                }
                if (!dark && d < bestOnHeat) {
                    bestOnHeat = d;
                    heatWay = way;
                    heatIndex = i;
                    heatPoint = q;
                }
            }
        }
        if (bestDark && heatWay != null) {
            bestWay = heatWay;
            bestIndex = heatIndex;
            bestPoint = heatPoint;
        }
        if (bestWay == null) {
            return null;
        }
        List<Node> nodes = new ArrayList<>(modified.getOrDefault(bestWay, bestWay.getNodes()));
        // Reuse an existing node closer than 3 meters
        double reuse = 3.0 / metersPerPixel;
        for (int i = bestIndex; i <= bestIndex + 1; i++) {
            if (window.toPixel(nodes.get(i).getCoor()).distance(bestPoint) < reuse) {
                return nodes.get(i);
            }
        }
        Node node = new Node(window.toLatLon(bestPoint.x - 0.5, bestPoint.y - 0.5));
        commands.add(new AddCommand(ds, node));
        nodes.add(bestIndex + 1, node);
        modified.put(bestWay, nodes);
        return node;
    }

    /** Hottest intensity in the 3x3 pixels around a point of the window. */
    private static int heatAround(HeatmapWindow window, Point2D.Double p) {
        int[][] intensity = window.getIntensity();
        int heat = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                heat = Math.max(heat, intensityAt(intensity, (int) Math.floor(p.x) + dx, (int) Math.floor(p.y) + dy));
            }
        }
        return heat;
    }

    /** Least intensity along the straight line between two points of the window. */
    private static int minHeat(HeatmapWindow window, Point2D.Double a, Point2D.Double b) {
        int[][] intensity = window.getIntensity();
        double length = a.distance(b);
        int min = Integer.MAX_VALUE;
        for (double s = 0; s <= length; s += 0.5) {
            double f = length == 0 ? 0 : s / length;
            min = Math.min(min, intensityAt(intensity, (int) Math.floor(a.x + f * (b.x - a.x)),
                    (int) Math.floor(a.y + f * (b.y - a.y))));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    private static int intensityAt(int[][] intensity, int x, int y) {
        return y >= 0 && x >= 0 && y < intensity.length && x < intensity[0].length ? intensity[y][x] : 0;
    }

    private static Point2D.Double nearestOnSegment(Point2D.Double p, Point2D.Double a, Point2D.Double b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
        return new Point2D.Double(a.x + t * dx, a.y + t * dy);
    }
}

