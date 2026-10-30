// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;

/**
 * Slides a line that is not in the data yet (a trace of the click mode) along the heatmap, before it is
 * created: the same fit as the Slide action, without the OSM context of existing nodes.
 */
public final class SlideNewLine {

    private SlideNewLine() {
    }

    /**
     * Slides a line.
     * @param info imagery of the Strava layer
     * @param locations [lon, lat] vertices of the line
     * @param fixed indices of the vertices that must stay in place (junctions with the other traced lines);
     *        the first and last vertices always stay in place
     * @param otherWays geometry of the roads and paths near the line, whose heat is theirs
     * @return the slid vertices, or the original ones when Slide finds no usable trail
     * @throws IOException if the heatmap cannot be loaded
     */
    public static List<double[]> slide(ImageryInfo info, List<double[]> locations, Set<Integer> fixed,
            List<List<double[]>> otherWays) throws IOException {
        if (locations.size() < 2) {
            return locations;
        }
        SlideTiles.Loaded loaded = SlideTiles.load(info, locations, () -> false, null);
        return slide(SlideAlgorithm.createHeatmapRaster(loaded.tiles, SlideTiles.SLIDE_ZOOM, loaded.bounds),
                locations, fixed, otherWays);
    }

    /** Slides a line on a raster of the heatmap around it. */
    static List<double[]> slide(SlideRaster raster, List<double[]> locations, Set<Integer> fixed,
            List<List<double[]>> otherWays) {
        SlideAlgorithm.Options options = new SlideAlgorithm.Options();
        options.fixedIndices.add(0);
        options.fixedIndices.add(locations.size() - 1);
        options.fixedIndices.addAll(fixed);
        options.otherWays = otherWays;
        List<Record> slid;
        try {
            slid = SlideAlgorithm.slideLineTwice(locations, raster, options);
        } catch (SlideAlgorithm.NoSignalException e) {
            return locations;
        }
        return valid(slid, locations, options.fixedIndices) ? locations(slid) : locations;
    }

    /** Tells if the fixed vertices are all kept, in order and in place (as the Slide action checks). */
    static boolean valid(List<Record> slid, List<double[]> locations, Set<Integer> fixed) {
        int expected = 0;
        List<Integer> order = new ArrayList<>(new java.util.TreeSet<>(fixed));
        for (Record r : slid) {
            if (r.loc == null || Double.isNaN(r.loc[0]) || Double.isNaN(r.loc[1])) {
                return false;
            }
            if (expected < order.size() && r.nodeIndex != null && r.nodeIndex.equals(order.get(expected))) {
                double[] loc = locations.get(r.nodeIndex);
                if (r.loc[0] != loc[0] || r.loc[1] != loc[1]) {
                    return false;
                }
                expected++;
            }
        }
        return expected == order.size() && slid.get(0).nodeIndex != null && slid.get(0).nodeIndex == 0
                && slid.get(slid.size() - 1).nodeIndex != null
                && slid.get(slid.size() - 1).nodeIndex == locations.size() - 1;
    }

    private static List<double[]> locations(List<Record> records) {
        List<double[]> result = new ArrayList<>();
        for (Record r : records) {
            result.add(r.loc);
        }
        return result;
    }
}
