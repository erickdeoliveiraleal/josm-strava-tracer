// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;

/**
 * Finds the parts of a band along a line that were not downloaded yet in a data set, like JOSM's
 * "Download along" but only for what is missing: Slide needs the roads and paths near the line (their heat
 * is theirs) and the ways connected to it (junctions do not move).
 */
final class DownloadAlong {

    /** Largest area (in square degrees, about 2 x 2 km) of one download request. */
    static final double MAX_REQUEST_AREA = 0.0004;
    /** Smallest missing area (in square degrees) worth a download: below, it is a rounding sliver. */
    private static final double MIN_MISSING_AREA = 1e-9;

    private DownloadAlong() {
    }

    /**
     * Returns the areas to download so that the band along the line is in the data set.
     * @param ds data set
     * @param line vertices of the line
     * @param meters half width of the band
     * @return the bounds of the download requests, empty if the band was already downloaded
     */
    static List<Bounds> missing(DataSet ds, List<LatLon> line, double meters) {
        Area band = band(line, meters);
        Area downloaded = ds.getDataSourceArea();
        if (downloaded != null) {
            band.subtract(downloaded);
        }
        List<Bounds> result = new ArrayList<>();
        if (band.isEmpty()) {
            return result;
        }
        List<Rectangle2D> rectangles = new ArrayList<>();
        split(band, band.getBounds2D(), rectangles);
        for (Rectangle2D r : rectangles) {
            result.add(new Bounds(r.getMinY(), r.getMinX(), r.getMaxY(), r.getMaxX()));
        }
        return result;
    }

    /** The band along the line, in degrees (x = longitude, y = latitude), as rectangles around its points. */
    private static Area band(List<LatLon> line, double meters) {
        Area band = new Area();
        double dLat = meters / 111_320;
        for (int i = 0; i < line.size(); i++) {
            LatLon a = line.get(i);
            LatLon b = i + 1 < line.size() ? line.get(i + 1) : a;
            // Points every half width along the segment, so that the rectangles overlap
            int steps = Math.max(1, (int) Math.ceil(a.greatCircleDistance(b) / meters * 2));
            for (int s = 0; s < steps; s++) {
                double lat = a.lat() + (b.lat() - a.lat()) * s / steps;
                double lon = a.lon() + (b.lon() - a.lon()) * s / steps;
                double dLon = dLat / Math.cos(Math.toRadians(lat));
                band.add(new Area(new Rectangle2D.Double(lon - dLon, lat - dLat, 2 * dLon, 2 * dLat)));
            }
        }
        return band;
    }

    /** Splits a rectangle in halves until each part is small enough, keeping the parts touching the area. */
    private static void split(Area area, Rectangle2D r, List<Rectangle2D> result) {
        Area part = new Area(r);
        part.intersect(area);
        if (part.isEmpty()) {
            return;
        }
        Rectangle2D used = part.getBounds2D();
        if (used.getWidth() * used.getHeight() < MIN_MISSING_AREA) {
            return;
        }
        if (used.getWidth() * used.getHeight() <= MAX_REQUEST_AREA) {
            result.add(used);
            return;
        }
        if (used.getWidth() >= used.getHeight()) {
            double half = used.getWidth() / 2;
            split(area, new Rectangle2D.Double(used.getMinX(), used.getMinY(), half, used.getHeight()), result);
            split(area, new Rectangle2D.Double(used.getMinX() + half, used.getMinY(), half, used.getHeight()), result);
        } else {
            double half = used.getHeight() / 2;
            split(area, new Rectangle2D.Double(used.getMinX(), used.getMinY(), used.getWidth(), half), result);
            split(area, new Rectangle2D.Double(used.getMinX(), used.getMinY() + half, used.getWidth(), half), result);
        }
    }
}
