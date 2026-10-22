// License: MIT. Port of strava-heatmap-extension by Julien ETIENNE (julcnx). For details, see the NOTICE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;

/**
 * The editor-independent rules of {@code slide/action.js} of strava-heatmap-extension v0.15.0 (MIT):
 * which vertices must not move, and the cleanup of the slid line (near-duplicate vertices, overlaps
 * with a connected way near a junction, spikes).
 */
public final class SlideCleanup {

    /** A vertex of the selected range. */
    public static final class Node {
        /** [longitude, latitude] */
        public final double[] loc;
        public final boolean tagged;
        /** Number of ways using the node (the selected way included). */
        public final int parentWays;
        public final boolean relationMember;
        /** Geometries ([lon, lat] lists) of the other ways with a highway tag using the node, in a stable order. */
        public final List<List<double[]>> otherHighways;

        public Node(double[] loc, boolean tagged, int parentWays, boolean relationMember,
                List<List<double[]>> otherHighways) {
            this.loc = loc;
            this.tagged = tagged;
            this.parentWays = parentWays;
            this.relationMember = relationMember;
            this.otherHighways = otherHighways;
        }
    }

    /** The selected range of a way. */
    public static final class Selection {
        public final List<Node> nodes;
        /** Index of the first selected vertex in the way. */
        public final int start;
        /** Index of the last selected vertex in the way. */
        public final int end;
        /** Number of vertices of the way. */
        public final int wayNodeCount;

        public Selection(List<Node> nodes, int start, int end, int wayNodeCount) {
            this.nodes = nodes;
            this.start = start;
            this.end = end;
            this.wayNodeCount = wayNodeCount;
        }
    }

    private SlideCleanup() {
    }

    /**
     * Indices of the vertices that must not move: tagged nodes, relation members, junctions, and the range
     * boundaries of a partial selection.
     * @param selection selected range
     * @return indices within the selection
     */
    public static Set<Integer> fixedIndices(Selection selection) {
        int last = selection.nodes.size() - 1;
        Set<Integer> result = new LinkedHashSet<>();
        for (int i = 0; i < selection.nodes.size(); i++) {
            Node node = selection.nodes.get(i);
            if (node.tagged || node.parentWays > 1 || node.relationMember
                    || (i == 0 && selection.start > 0)
                    || (i == last && selection.end < selection.wayNodeCount - 1)) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Indices of the vertices that must never be deleted: tagged nodes, junctions and relation members.
     * @param selection selected range
     * @return indices within the selection
     */
    public static Set<Integer> protectedIndices(Selection selection) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int i = 0; i < selection.nodes.size(); i++) {
            Node node = selection.nodes.get(i);
            if (node.tagged || node.parentWays > 1 || node.relationMember) {
                result.add(i);
            }
        }
        return result;
    }

    // Vertices closer together than this are redundant (near-duplicates).
    static final double CLOSE_METERS = 1.5;
    static final double ALONG_LINE_METERS = 2 * CLOSE_METERS;
    // Next to a junction, a slid vertex this close to the other way is still on that way's trail.
    static final double OVERLAP_METERS = 8;
    // A slid line staying that close to the other way for longer runs along it, on its trail.
    static final double MAX_OVERLAP_METERS = 30;
    // A single vertex where the line turns back by more than this is a spike.
    static final double SPIKE_TURN_DEGREES = 150;

    static double distanceMeters(double[] a, double[] b) {
        double meanLat = (((a[1] + b[1]) / 2) * Math.PI) / 180;
        return Math.hypot((b[0] - a[0]) * Math.cos(meanLat) * 111319.49, (b[1] - a[1]) * 110574);
    }

    private static double[] toMeters(double[] loc, double[] origin) {
        return new double[] {
            (loc[0] - origin[0]) * Math.cos((origin[1] * Math.PI) / 180) * 111319.49,
            (loc[1] - origin[1]) * 110574,
        };
    }

    private static double[] nearestOnSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length = dx * dx + dy * dy;
        double t = length != 0
                ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length))
                : 0;
        return new double[] {a[0] + t * dx, a[1] + t * dy};
    }

    private static int indexOfNode(List<Record> points, int nodeIndex) {
        for (int i = 0; i < points.size(); i++) {
            Integer n = points.get(i).nodeIndex;
            if (n != null && n == nodeIndex) {
                return i;
            }
        }
        return -1;
    }

    // Near a junction both trails share heat: from each junction, drop the slid vertices lying within
    // OVERLAP_METERS of the other way; a longer overlap is left as drawn (counted in alongOther).
    static Result trimJunctionOverlaps(Selection selection, List<Record> result) {
        Set<Integer> protectedIndexes = new LinkedHashSet<>();
        protectedIndexes.add(0);
        protectedIndexes.add(selection.nodes.size() - 1);
        protectedIndexes.addAll(protectedIndices(selection));
        List<Record> points = new ArrayList<>();
        for (Record point : result) {
            Record copy = point.copy();
            copy.loc = point.loc.clone();
            points.add(copy);
        }
        int alongOther = 0;
        for (int index = 0; index < selection.nodes.size(); index++) {
            Node node = selection.nodes.get(index);
            for (List<double[]> other : node.otherHighways) {
                if (indexOfNode(points, index) < 0) {
                    continue;
                }
                double[] origin = node.loc;
                List<double[]> line = new ArrayList<>();
                for (double[] loc : other) {
                    line.add(toMeters(loc, origin));
                }
                for (int step : new int[] {-1, 1}) {
                    int r = indexOfNode(points, index);
                    int k = r + step;
                    while (k > 0 && k < points.size() - 1 && removable(points.get(k), protectedIndexes, selection)
                            && distance(points.get(k).loc, line, origin) < OVERLAP_METERS) {
                        k += step;
                    }
                    int overlap = Math.abs(k - r) - 1;
                    if (overlap <= 0) {
                        continue;
                    }
                    double length = 0;
                    for (int m = r; m != k - step; m += step) {
                        length += distanceMeters(points.get(m).loc, points.get(m + step).loc);
                    }
                    if (length > MAX_OVERLAP_METERS) {
                        alongOther++;
                        for (int m = r + step; m != k; m += step) {
                            if (points.get(m).nodeIndex != null) {
                                points.get(m).loc = selection.nodes.get(points.get(m).nodeIndex).loc.clone();
                            }
                        }
                        List<Record> filtered = new ArrayList<>();
                        for (int i = 0; i < points.size(); i++) {
                            if (points.get(i).nodeIndex != null || i <= Math.min(r, k) || i >= Math.max(r, k)) {
                                filtered.add(points.get(i));
                            }
                        }
                        points = filtered;
                        continue;
                    }
                    int from = Math.min(r + step, k - step);
                    int to = Math.max(r + step, k - step);
                    List<Record> filtered = new ArrayList<>();
                    for (int i = 0; i < points.size(); i++) {
                        if (i < from || i > to) {
                            filtered.add(points.get(i));
                        }
                    }
                    points = filtered;
                }
            }
        }
        Result trimmed = new Result(points);
        trimmed.alongOther = alongOther;
        return trimmed;
    }

    private static boolean removable(Record point, Set<Integer> protectedIndexes, Selection selection) {
        return point.nodeIndex == null || (!protectedIndexes.contains(point.nodeIndex)
                && distanceMeters(point.loc, selection.nodes.get(point.nodeIndex).loc) >= CLOSE_METERS);
    }

    private static double distance(double[] loc, List<double[]> line, double[] origin) {
        double best = Double.POSITIVE_INFINITY;
        double[] p = toMeters(loc, origin);
        for (int k = 1; k < line.size(); k++) {
            double[] q = nearestOnSegment(p, line.get(k - 1), line.get(k));
            best = Math.min(best, Math.hypot(p[0] - q[0], p[1] - q[1]));
        }
        return best;
    }

    /**
     * Cleans the slid line: junction overlaps, near-duplicate vertices (Slide's own first), then spikes.
     * Idempotent.
     * @param selection selected range
     * @param input result of slideLineTwice
     * @return the cleaned points, with the number of stretches along a connected way left as drawn
     */
    public static Result cleanSlideResult(Selection selection, List<Record> input) {
        Result result = trimJunctionOverlaps(selection, input);
        Set<Integer> protectedIndexes = new LinkedHashSet<>();
        protectedIndexes.add(0);
        protectedIndexes.add(selection.nodes.size() - 1);
        protectedIndexes.addAll(protectedIndices(selection));
        int size = result.size();
        boolean[] removable = new boolean[size];
        for (int index = 0; index < size; index++) {
            Integer n = result.get(index).nodeIndex;
            removable[index] = index != 0 && index != size - 1 && (n == null || !protectedIndexes.contains(n));
        }
        // "Deletion rank": slide-created (0) loses to a deletable original (1), which loses to anything protected.
        double[] rank = new double[size];
        for (int index = 0; index < size; index++) {
            rank[index] = !removable[index] ? Double.POSITIVE_INFINITY : result.get(index).nodeIndex == null ? 0 : 1;
        }
        Set<Integer> dropped = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            if (dropped.contains(i)) {
                continue;
            }
            double along = 0;
            for (int j = i + 1; j < size; j++) {
                along += distanceMeters(result.get(j - 1).loc, result.get(j).loc);
                if (along > ALONG_LINE_METERS) {
                    break;
                }
                if (dropped.contains(j)) {
                    continue;
                }
                if (distanceMeters(result.get(i).loc, result.get(j).loc) >= CLOSE_METERS) {
                    continue;
                }
                double a = rank[i];
                double b = rank[j];
                if (a == Double.POSITIVE_INFINITY && b == Double.POSITIVE_INFINITY) {
                    continue;
                }
                dropped.add(a < b ? i : j);
            }
        }
        // Remove spikes, sharpest first, until none are left.
        List<Integer> kept = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            if (!dropped.contains(index)) {
                kept.add(index);
            }
        }
        for (;;) {
            int spike = -1;
            double sharpest = SPIKE_TURN_DEGREES;
            for (int k = 1; k < kept.size() - 1; k++) {
                if (!removable[kept.get(k)]) {
                    continue;
                }
                double value = turn(result, kept.get(k - 1), kept.get(k), kept.get(k + 1));
                if (value > sharpest) {
                    spike = k;
                    sharpest = value;
                }
            }
            if (spike == -1) {
                break;
            }
            kept.remove(spike);
        }
        Result cleaned = new Result();
        for (int index : kept) {
            cleaned.add(result.get(index));
        }
        cleaned.alongOther = result.alongOther;
        cleaned.skipped = input instanceof Result ? ((Result) input).skipped : 0;
        return cleaned;
    }

    private static double turn(List<Record> result, int a, int b, int c) {
        double meanLat = (result.get(b).loc[1] * Math.PI) / 180;
        double[] u = {(result.get(b).loc[0] - result.get(a).loc[0]) * Math.cos(meanLat),
            result.get(b).loc[1] - result.get(a).loc[1]};
        double[] v = {(result.get(c).loc[0] - result.get(b).loc[0]) * Math.cos(meanLat),
            result.get(c).loc[1] - result.get(b).loc[1]};
        double length = Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]);
        if (length == 0) {
            return 0;
        }
        double cosine = (u[0] * v[0] + u[1] * v[1]) / length;
        return (Math.acos(Math.max(-1, Math.min(1, cosine))) * 180) / Math.PI;
    }
}
