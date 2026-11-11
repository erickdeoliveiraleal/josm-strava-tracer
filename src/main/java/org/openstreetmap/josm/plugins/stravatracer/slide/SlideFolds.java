// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;

/**
 * Removes the small loops and folds of a slid line: where the drawn vertices are not in order along the trail
 * (a rough trace through a blob of heat), the trail is followed back and forth between them and the line runs
 * several times over itself.
 */
final class SlideFolds {

    /** A line coming back this close to where it already went has made a loop. */
    static final double CLOSE_METERS = 2.5;
    /** Longest loop removed: a longer one is a loop of the trail itself. */
    static final double MAX_LOOP_METERS = 50;
    /** A vertex turning back more than this, with a short leg, is a spike. */
    static final double SPIKE_DEGREES = 150;
    static final double SPIKE_LEG_METERS = 5;

    private SlideFolds() {
    }

    /**
     * Removes the loops and spikes of a slid line, never the first and last points and the points of the given
     * original vertices.
     * @param records slid line
     * @param keep indices of original vertices that must stay (junctions, tagged nodes…)
     * @return the line without loops, with the counters of the given one
     */
    static Result remove(List<Record> records, Set<Integer> keep) {
        Result result = new Result();
        if (records instanceof Result) {
            result.skipped = ((Result) records).skipped;
            result.alongOther = ((Result) records).alongOther;
        }
        List<Record> line = new ArrayList<>(records);
        if (line.size() > 3) {
            double cos = Math.cos(Math.toRadians(line.get(0).loc[1]));
            while (cutLoop(line, keep, cos) || cutSpike(line, keep, cos)) {
                // Again, until none is left
            }
        }
        result.addAll(line);
        return result;
    }

    private static double[] xy(Record r, double cos) {
        return new double[] {r.loc[0] * 111_320 * cos, r.loc[1] * 110_540};
    }

    private static boolean removable(List<Record> line, int from, int to, Set<Integer> keep) {
        if (from <= 0 || to >= line.size() - 1) {
            return false;
        }
        for (int i = from; i <= to; i++) {
            Integer index = line.get(i).nodeIndex;
            if (index != null && keep.contains(index)) {
                return false;
            }
        }
        return true;
    }

    /** Cuts the first loop: the line comes back near a segment it went along shortly before. */
    private static boolean cutLoop(List<Record> line, Set<Integer> keep, double cos) {
        for (int j = 2; j < line.size(); j++) {
            double[] q = xy(line.get(j), cos);
            double along = 0;
            for (int k = j - 2; k >= 0; k--) {
                double[] a = xy(line.get(k), cos);
                double[] b = xy(line.get(k + 1), cos);
                along += Math.hypot(xy(line.get(k + 2), cos)[0] - b[0], xy(line.get(k + 2), cos)[1] - b[1]);
                double dx = b[0] - a[0];
                double dy = b[1] - a[1];
                double length2 = dx * dx + dy * dy;
                double t = length2 == 0 ? 0
                        : Math.max(0, Math.min(1, ((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / length2));
                double d = Math.hypot(q[0] - a[0] - t * dx, q[1] - a[1] - t * dy);
                double loop = along + Math.sqrt(length2) * (1 - t);
                if (loop > MAX_LOOP_METERS) {
                    break;
                }
                if (d < CLOSE_METERS && loop > 3 * d + 2 * CLOSE_METERS && removable(line, k + 1, j - 1, keep)) {
                    line.subList(k + 1, j).clear();
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes the first spike: a vertex where the line turns back, with a short leg. */
    private static boolean cutSpike(List<Record> line, Set<Integer> keep, double cos) {
        for (int i = 1; i < line.size() - 1; i++) {
            double[] a = xy(line.get(i - 1), cos);
            double[] p = xy(line.get(i), cos);
            double[] b = xy(line.get(i + 1), cos);
            double[] u = {p[0] - a[0], p[1] - a[1]};
            double[] v = {b[0] - p[0], b[1] - p[1]};
            double lu = Math.hypot(u[0], u[1]);
            double lv = Math.hypot(v[0], v[1]);
            if (lu == 0 || lv == 0 || Math.min(lu, lv) >= SPIKE_LEG_METERS) {
                continue;
            }
            double turn = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (u[0] * v[0] + u[1] * v[1]) / (lu * lv)))));
            if (turn > SPIKE_DEGREES && removable(line, i, i, keep)) {
                line.remove(i);
                return true;
            }
        }
        return false;
    }
}
