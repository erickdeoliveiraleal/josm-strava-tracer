// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;

/**
 * Removes the vertices of a slid line that do not change its shape by more than a tolerance: the smoothing of
 * Slide keeps a vertex every few metres in bends, finer than the heatmap itself.
 */
final class SlideSimplify {

    private SlideSimplify() {
    }

    /**
     * Simplifies a slid line (Douglas-Peucker). The first and last points and the points of the given original
     * vertices are always kept.
     * @param records slid line
     * @param keep indices of original vertices that must stay (junctions, tagged nodes…)
     * @param meters tolerance; 0 or less returns the line unchanged
     * @return the simplified line, with the counters of the given one
     */
    static Result simplify(List<Record> records, Set<Integer> keep, double meters) {
        Result result = new Result();
        if (records instanceof Result) {
            result.skipped = ((Result) records).skipped;
            result.alongOther = ((Result) records).alongOther;
        }
        if (meters <= 0 || records.size() < 3) {
            result.addAll(records);
            return result;
        }
        double cos = Math.cos(Math.toRadians(records.get(0).loc[1]));
        double[][] p = new double[records.size()][];
        for (int i = 0; i < p.length; i++) {
            p[i] = new double[] {records.get(i).loc[0] * 111_320 * cos, records.get(i).loc[1] * 110_540};
        }
        boolean[] kept = new boolean[p.length];
        kept[0] = true;
        kept[p.length - 1] = true;
        for (int i = 1; i < p.length - 1; i++) {
            Integer index = records.get(i).nodeIndex;
            if (index != null && keep.contains(index)) {
                kept[i] = true;
            }
        }
        int from = 0;
        for (int i = 1; i < p.length; i++) {
            if (kept[i]) {
                douglasPeucker(p, from, i, meters, kept);
                from = i;
            }
        }
        for (int i = 0; i < p.length; i++) {
            if (kept[i]) {
                result.add(records.get(i));
            }
        }
        return result;
    }

    private static void douglasPeucker(double[][] p, int first, int last, double tolerance, boolean[] kept) {
        if (last - first < 2) {
            return;
        }
        double max = -1;
        int farthest = -1;
        for (int i = first + 1; i < last; i++) {
            double d = distanceToSegment(p[i], p[first], p[last]);
            if (d > max) {
                max = d;
                farthest = i;
            }
        }
        if (max > tolerance) {
            kept[farthest] = true;
            douglasPeucker(p, first, farthest, tolerance, kept);
            douglasPeucker(p, farthest, last, tolerance, kept);
        }
    }

    private static double distanceToSegment(double[] q, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length2 = dx * dx + dy * dy;
        double t = length2 == 0 ? 0 : Math.max(0, Math.min(1, ((q[0] - a[0]) * dx + (q[1] - a[1]) * dy) / length2));
        return Math.hypot(q[0] - a[0] - t * dx, q[1] - a[1] - t * dy);
    }
}
