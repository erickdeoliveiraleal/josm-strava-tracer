// License: MIT. Port of strava-heatmap-extension by Julien ETIENNE (julcnx). For details, see the NOTICE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Strava Slide – the heatmap-snapping engine.
 * <p>
 * Faithful Java port of {@code src/clients/www.openstreetmap.org/slide/algorithm.js} of
 * <a href="https://github.com/julcnx/strava-heatmap-extension">strava-heatmap-extension</a> v0.15.0
 * (MIT license), itself based on <a href="https://github.com/paulmach/slide">paulmach/slide</a>.
 * The functions, constants and comments follow the original so that both can be compared and the
 * upstream improvements can be ported. Typed arrays of the original ({@code Float32Array}) are
 * {@code float[]} here, so that the roundings are the same.
 * <p>
 * Slide takes a roughly digitised OSM line and refits it onto the trail that people actually
 * recorded on the Strava heatmap:
 * <ol>
 * <li>Downloaded tiles are merged into one raster – createHeatmapRaster().</li>
 * <li>A locally contrast-normalised copy ("tracking surface") is built so a faint but coherent
 * trail is as visible as a bright road.</li>
 * <li>Every original vertex is snapped onto the nearest trail point ("anchor"); fixed vertices
 * (endpoints, junctions) keep their position.</li>
 * <li>Between consecutive anchors, a corridor-constrained shortest path over the raster grid
 * follows the density ridge – trace().</li>
 * <li>That pixel path is simplified to a few vertices – simplify().</li>
 * </ol>
 * Terminology: loc = [longitude, latitude]; point = fractional [x, y] in projected pixel space;
 * density = heat strength in 0..1; anchor = the trail point that one original vertex snapped to;
 * segment = the piece of the line between two consecutive original vertices.
 */
public final class SlideAlgorithm {

    static final int TILE_SIZE = 512;
    static final double EARTH_CIRCUMFERENCE = 40075016.68557849;

    // Tuning knobs, all in metres on the ground (see SLIDE_DEFAULTS in the original)
    public static final double SEARCH_RADIUS_METERS = 24;
    public static final double MAX_SEARCH_RADIUS_METERS = 80;
    public static final double SNAP_RADIUS_METERS = 18;
    public static final double MAX_SNAP_RADIUS_METERS = 60;
    public static final double SIMPLIFY_METERS = 2;
    public static final double CONTRAST_RADIUS_METERS = 12;
    public static final double MIN_TRIANGLE_SQUARE_METERS = 10;
    public static final double AVOID_METERS = 4;
    public static final double APPROACH_FACTOR = 3;
    public static final double OTHER_WAY_METERS = 5;
    public static final double OTHER_WAY_FADE_METERS = 8;
    public static final double OTHER_WAY_FLANK_METERS = 20;
    public static final double JUNCTION_METERS = 8;
    public static final double JUMP_METERS = 8;
    public static final double ALIGN_METERS = 6;

    /**
     * Largest raster (in pixels). The original allows 4 million pixels, a limit made for a browser tab; JOSM
     * has more memory, so long lines can be slid in one go (see SlideTiles for the memory check).
     */
    public static final double MAX_RASTER_PIXELS = 40_000_000;

    /** "There is no heatmap trail here": an expected outcome, not a bug (SLIDE_NO_SIGNAL). */
    public static class NoSignalException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public NoSignalException(String message) {
            super(message);
        }
    }

    /** Thrown when the run is cancelled (AbortError). */
    public static class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Slide cancelled.");
        }
    }

    /** A point of the slid line. */
    public static final class Record {
        /** [longitude, latitude] */
        public double[] loc;
        /** Index of the original vertex, or {@code null} for a point Slide created. */
        public Integer nodeIndex;
        /** The vertex stays as drawn. */
        public boolean drawn;
        /** The section ending here was left as drawn. */
        public boolean untraced;

        public Record(double[] loc, Integer nodeIndex) {
            this.loc = loc;
            this.nodeIndex = nodeIndex;
        }

        Record copy() {
            Record r = new Record(loc, nodeIndex);
            r.drawn = drawn;
            r.untraced = untraced;
            return r;
        }

        @Override
        public String toString() {
            return Arrays.toString(loc) + "#" + nodeIndex;
        }
    }

    /** The slid line, with the number of sections left as drawn. */
    public static final class Result extends ArrayList<Record> {
        private static final long serialVersionUID = 1L;
        public int skipped;
        /** Sections following a connected way's trail left as drawn (see SlideCleanup). */
        public int alongOther;

        public Result() {
        }

        public Result(Collection<Record> records) {
            super(records);
        }
    }

    /** Options of slideLine() and slideLineTwice(). */
    public static final class Options {
        public Set<Integer> fixedIndices = new LinkedHashSet<>();
        public Set<Integer> limitedIndices = new LinkedHashSet<>();
        public Map<Integer, double[]> limitedOrigins;
        public Set<Integer> untracedSections = new LinkedHashSet<>();
        public boolean holdBends = true;
        public Double alignMeters;
        public Double searchRadiusMeters;
        public Double snapRadiusMeters;
        public Double maxSearchRadiusMeters;
        public Double maxSnapRadiusMeters;
        public Double simplifyMeters;
        public Double minTriangleSquareMeters;
        /** Nearby mapped roads and paths as lists of [lon, lat], whose heat is ignored. */
        public List<List<double[]>> otherWays = new ArrayList<>();
        public BooleanSupplier cancelled = () -> false;
        public DoubleConsumer onProgress;
        double scale;
        byte[] avoid;
        double avoidExclude;

        public Options copy() {
            Options o = new Options();
            o.fixedIndices = new LinkedHashSet<>(fixedIndices);
            o.limitedIndices = new LinkedHashSet<>(limitedIndices);
            o.limitedOrigins = limitedOrigins;
            o.untracedSections = new LinkedHashSet<>(untracedSections);
            o.holdBends = holdBends;
            o.alignMeters = alignMeters;
            o.searchRadiusMeters = searchRadiusMeters;
            o.snapRadiusMeters = snapRadiusMeters;
            o.maxSearchRadiusMeters = maxSearchRadiusMeters;
            o.maxSnapRadiusMeters = maxSnapRadiusMeters;
            o.simplifyMeters = simplifyMeters;
            o.minTriangleSquareMeters = minTriangleSquareMeters;
            o.otherWays = otherWays;
            o.cancelled = cancelled;
            o.onProgress = onProgress;
            o.scale = scale;
            o.avoid = avoid;
            o.avoidExclude = avoidExclude;
            return o;
        }
    }

    /** A decoded heatmap tile: heat density 0..1 of a (z, x, y) tile. */
    public static final class Tile {
        public final int z;
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final float[] density;

        public Tile(int z, int x, int y, int width, int height, float[] density) {
            this.z = z;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.density = density;
        }
    }

    /** Pixel bounds (inclusive). */
    public static final class Bounds {
        public final int minX;
        public final int minY;
        public final int maxX;
        public final int maxY;

        public Bounds(int minX, int minY, int maxX, int maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private SlideAlgorithm() {
    }

    // Project [longitude, latitude] into Web-Mercator pixel coordinates at the given zoom.
    public static double[] project(double[] loc, int zoom) {
        double size = TILE_SIZE * Math.pow(2, zoom);
        double lat = (Math.max(-85.05112878, Math.min(85.05112878, loc[1])) * Math.PI) / 180;
        return new double[] {
            ((loc[0] + 180) / 360) * size,
            ((1 - asinh(Math.tan(lat)) / Math.PI) / 2) * size,
        };
    }

    // The exact inverse of project().
    public static double[] unproject(double[] point, int zoom) {
        double size = TILE_SIZE * Math.pow(2, zoom);
        return new double[] {
            (point[0] / size) * 360 - 180,
            (Math.atan(Math.sinh(Math.PI * (1 - (2 * point[1]) / size))) * 180) / Math.PI,
        };
    }

    private static double asinh(double x) {
        // Same formula as V8's Math.asinh for the latitudes of the map
        if (x == 0) {
            return x;
        }
        double ax = Math.abs(x);
        double r = Math.log(ax + Math.sqrt(ax * ax + 1));
        return x < 0 ? -r : r;
    }

    // Ground width of one pixel at a latitude, in metres.
    public static double metersPerPixel(double latitude, int zoom) {
        return (EARTH_CIRCUMFERENCE * Math.cos((latitude * Math.PI) / 180)) / (TILE_SIZE * Math.pow(2, zoom));
    }

    // Projected bounding box of a line, expanded by `padding` pixels on every side.
    public static Bounds lineBounds(List<double[]> locations, int zoom, double padding) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (double[] loc : locations) {
            double[] p = project(loc, zoom);
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new Bounds((int) Math.floor(minX - padding), (int) Math.floor(minY - padding),
                (int) Math.ceil(maxX + padding), (int) Math.ceil(maxY + padding));
    }

    // Smooth the raster slightly (small separable Gaussian: horizontally, then vertically).
    static float[] blur(float[] data, int width, int height) {
        double[] kernel = {0.0545, 0.2442, 0.4026, 0.2442, 0.0545};
        float[] temp = new float[data.length];
        float[] output = new float[data.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = -2; k <= 2; k++) {
                    temp[y * width + x] = (float) (temp[y * width + x]
                            + data[y * width + Math.max(0, Math.min(width - 1, x + k))] * kernel[k + 2]);
                }
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = -2; k <= 2; k++) {
                    output[y * width + x] = (float) (output[y * width + x]
                            + temp[Math.max(0, Math.min(height - 1, y + k)) * width + x] * kernel[k + 2]);
                }
            }
        }
        return output;
    }

    // Merge decoded tiles into a single raster covering `bounds`.
    public static SlideRaster createHeatmapRaster(List<Tile> tiles, int zoom, Bounds bounds) {
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("No heatmap tiles are available.");
        }
        final class Extent {
            final Tile tile;
            final double scale;
            final double x;
            final double y;

            Extent(Tile tile) {
                this.tile = tile;
                this.scale = TILE_SIZE * Math.pow(2, zoom - tile.z);
                this.x = tile.x * scale;
                this.y = tile.y * scale;
            }
        }
        List<Extent> extents = new ArrayList<>();
        for (Tile tile : tiles) {
            extents.add(new Extent(tile));
        }
        if (bounds == null) {
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (Extent e : extents) {
                minX = Math.min(minX, e.x);
                minY = Math.min(minY, e.y);
                maxX = Math.max(maxX, e.x + e.scale);
                maxY = Math.max(maxY, e.y + e.scale);
            }
            bounds = new Bounds((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.ceil(maxX),
                    (int) Math.ceil(maxY));
        }
        int width = bounds.maxX - bounds.minX + 1;
        int height = bounds.maxY - bounds.minY + 1;
        if (width < 2 || height < 2 || (double) width * height > MAX_RASTER_PIXELS) {
            throw new IllegalArgumentException("Slide area is too large. Select a shorter section of the line.");
        }
        float[] density = new float[width * height];
        byte[] coverage = new byte[width * height];
        // Finest first (stable, like Array.prototype.sort)
        extents.sort((a, b) -> Double.compare(b.tile.width / b.scale, a.tile.width / a.scale));
        for (Extent e : extents) {
            Tile tile = e.tile;
            if (tile.density.length != tile.width * tile.height) {
                throw new IllegalArgumentException("Invalid heatmap tile pixels.");
            }
            double scale = e.scale;
            double tx = e.x;
            double ty = e.y;
            for (int y = (int) Math.max(bounds.minY, Math.ceil(ty)); y < Math.min(bounds.maxY + 1, ty + scale); y++) {
                for (int x = (int) Math.max(bounds.minX, Math.ceil(tx)); x < Math.min(bounds.maxX + 1, tx + scale);
                        x++) {
                    int index = (y - bounds.minY) * width + x - bounds.minX;
                    if (coverage[index] != 0) {
                        continue;
                    }
                    // Image samples live at pixel centers, not at the tile's upper-left pixel edges
                    double px = Math.max(0, Math.min(tile.width - 1, ((x - tx) / scale) * tile.width - 0.5));
                    double py = Math.max(0, Math.min(tile.height - 1, ((y - ty) / scale) * tile.height - 0.5));
                    int x0 = (int) Math.floor(px);
                    int y0 = (int) Math.floor(py);
                    int x1 = Math.min(x0 + 1, tile.width - 1);
                    int y1 = Math.min(y0 + 1, tile.height - 1);
                    double dx = px - x0;
                    double dy = py - y0;
                    density[index] = (float) ((value(tile, x0, y0) * (1 - dx) + value(tile, x1, y0) * dx) * (1 - dy)
                            + (value(tile, x0, y1) * (1 - dx) + value(tile, x1, y1) * dx) * dy);
                    coverage[index] = 1;
                }
            }
        }
        // Shift the range so the darkest covered pixel becomes exactly 0 and the scale stays 0..1
        double floor = 1;
        for (int i = 0; i < density.length; i++) {
            if (coverage[i] != 0) {
                floor = Math.min(floor, density[i]);
            }
        }
        for (int i = 0; i < density.length; i++) {
            density[i] = (float) Math.max(0, (density[i] - floor) / Math.max(0.1, 1 - floor));
        }
        return new SlideRaster(width, height, bounds.minX, bounds.minY, zoom, blur(density, width, height),
                coverage, null, null);
    }

    private static double value(Tile tile, int x, int y) {
        return tile.density[y * tile.width + x];
    }

    // Bilinear interpolation of the density at a fractional pixel point; points outside read 0.
    public static double sampleDensity(SlideRaster raster, double[] point) {
        return sample(raster, raster.density, point);
    }

    private static double sample(SlideRaster raster, float[] density, double[] point) {
        double x = point[0] - raster.x;
        double y = point[1] - raster.y;
        if (x < 0 || y < 0 || x >= raster.width - 1 || y >= raster.height - 1) {
            return 0;
        }
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        double dx = x - x0;
        double dy = y - y0;
        int i = y0 * raster.width + x0;
        return (density[i] * (1 - dx) + density[i + 1] * dx) * (1 - dy)
                + (density[i + raster.width] * (1 - dx) + density[i + raster.width + 1] * dx) * dy;
    }

    // Shortest distance from point p to the segment a–b (0 for degenerate segments).
    static double distanceToSegment(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double length = dx * dx + dy * dy;
        double t = length != 0
                ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length))
                : 0;
        return Math.hypot(p[0] - a[0] - dx * t, p[1] - a[1] - dy * t);
    }

    // Minimal binary min-heap keyed by cost (same algorithm as the original, for the same ties).
    private static final class MinHeap {
        int[] indices = new int[64];
        double[] costs = new double[64];
        int size;

        void push(int index, double cost) {
            if (size == indices.length) {
                indices = Arrays.copyOf(indices, size * 2);
                costs = Arrays.copyOf(costs, size * 2);
            }
            int i = size++;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (costs[parent] <= cost) {
                    break;
                }
                indices[i] = indices[parent];
                costs[i] = costs[parent];
                i = parent;
            }
            indices[i] = index;
            costs[i] = cost;
        }

        /** @return the index of the cheapest item */
        int pop() {
            int first = indices[0];
            size--;
            int lastIndex = indices[size];
            double lastCost = costs[size];
            if (size > 0) {
                int i = 0;
                while (i * 2 + 1 < size) {
                    int child = i * 2 + 1;
                    if (child + 1 < size && costs[child + 1] < costs[child]) {
                        child++;
                    }
                    if (costs[child] >= lastCost) {
                        break;
                    }
                    indices[i] = indices[child];
                    costs[i] = costs[child];
                    i = child;
                }
                indices[i] = lastIndex;
                costs[i] = lastCost;
            }
            return first;
        }
    }

    private static void checkAbort(Options options) {
        if (options.cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
    }

    // Whether a raster pixel is actually backed by downloaded imagery.
    static boolean isCovered(SlideRaster raster, double[] point) {
        int x = (int) Math.floor(point[0]) - raster.x;
        int y = (int) Math.floor(point[1]) - raster.y;
        return x >= 0 && y >= 0 && x < raster.width && y < raster.height && raster.coverage[y * raster.width + x] != 0;
    }

    // Radius ladder for adaptive widening: start small and double until `maximum`.
    static List<Double> searchRadii(double initial, double maximum) {
        List<Double> radii = new ArrayList<>();
        radii.add(initial);
        while (radii.get(radii.size() - 1) < maximum) {
            radii.add(Math.min(maximum, radii.get(radii.size() - 1) * 2));
        }
        return radii;
    }

    // Heat on other mapped roads and paths belongs to them (see the original for the details).
    static final double FLANK_DROP = 0.97;
    static final double NEAR_METERS = 10;
    static final double FLANK_CORE = 0.85;
    static final double FLANK_DARK = 0.02;

    private static boolean onFlank(SlideRaster raster, double[] from, double[] to, double inner, double metre) {
        double length = Math.hypot(to[0] - from[0], to[1] - from[1]);
        java.util.function.DoubleUnaryOperator at = d -> sampleDensity(raster, new double[] {
            from[0] + ((to[0] - from[0]) * d) / length,
            from[1] + ((to[1] - from[1]) * d) / length,
        });
        double start = at.applyAsDouble(inner);
        if (start <= FLANK_DARK) {
            return false;
        }
        double previous = start;
        boolean falling = false;
        for (double d = inner + metre; d < length + metre; d += metre) {
            double value = at.applyAsDouble(Math.min(d, length));
            if (!falling) {
                // Rising well above the start: another trail's band, not this core.
                if (value > start / FLANK_CORE) {
                    return false;
                }
                falling = value < start * FLANK_CORE;
            } else if (value > FLANK_DARK && value > previous * FLANK_DROP) {
                return false;
            }
            previous = value;
        }
        return true;
    }

    static SlideRaster maskOtherWays(SlideRaster raster, List<List<double[]>> otherWays, List<double[]> locations,
            double scale) {
        int width = raster.width;
        int height = raster.height;
        double inner = OTHER_WAY_METERS / scale;
        double outer = OTHER_WAY_FADE_METERS / scale;
        double flank = OTHER_WAY_FLANK_METERS / scale;
        double junctionRadius = JUNCTION_METERS / scale;
        double metre = 1 / scale;
        List<double[]> junctions = new ArrayList<>();
        for (double[] loc : locations) {
            boolean junction = false;
            for (List<double[]> way : otherWays) {
                for (double[] o : way) {
                    if (o[0] == loc[0] && o[1] == loc[1]) {
                        junction = true;
                        break;
                    }
                }
                if (junction) {
                    break;
                }
            }
            if (junction) {
                junctions.add(project(loc, raster.zoom));
            }
        }
        float[] weight = new float[width * height];
        Arrays.fill(weight, 1);
        byte[] near = new byte[width * height];
        for (List<double[]> way : otherWays) {
            List<double[]> points = new ArrayList<>();
            for (double[] loc : way) {
                points.add(project(loc, raster.zoom));
            }
            for (int k = 1; k < points.size(); k++) {
                double[] a = points.get(k - 1);
                double[] b = points.get(k);
                int minX = (int) Math.max(0, Math.floor(Math.min(a[0], b[0]) - flank) - raster.x);
                int maxX = (int) Math.min(width - 1, Math.ceil(Math.max(a[0], b[0]) + flank) - raster.x);
                int minY = (int) Math.max(0, Math.floor(Math.min(a[1], b[1]) - flank) - raster.y);
                int maxY = (int) Math.min(height - 1, Math.ceil(Math.max(a[1], b[1]) + flank) - raster.y);
                double dx = b[0] - a[0];
                double dy = b[1] - a[1];
                double length = dx * dx + dy * dy;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        int index = y * width + x;
                        if (weight[index] == 0) {
                            continue;
                        }
                        double[] p = {x + raster.x, y + raster.y};
                        double t = length != 0
                                ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length))
                                : 0;
                        double[] nearest = {a[0] + dx * t, a[1] + dy * t};
                        double d = Math.hypot(p[0] - nearest[0], p[1] - nearest[1]);
                        if (d > flank) {
                            continue;
                        }
                        if (d < NEAR_METERS / scale) {
                            near[index] = 1;
                        } else if (near[index] == 0) {
                            near[index] = 2;
                        }
                        double w = Math.max(0, Math.min(1, (d - inner) / (outer - inner)));
                        if (w > 0 && d > inner) {
                            boolean nearJunction = false;
                            for (double[] j : junctions) {
                                if (Math.hypot(p[0] - j[0], p[1] - j[1]) <= flank) {
                                    nearJunction = true;
                                    break;
                                }
                            }
                            if (!nearJunction && onFlank(raster, nearest, p, inner, metre)) {
                                w = 0;
                            }
                        }
                        if (w < weight[index]) {
                            weight[index] = (float) w;
                        }
                    }
                }
            }
        }
        for (double[] j : junctions) {
            double jx = j[0];
            double jy = j[1];
            for (int y = (int) Math.max(0, Math.floor(jy - junctionRadius) - raster.y);
                    y <= Math.min(height - 1, Math.ceil(jy + junctionRadius) - raster.y); y++) {
                for (int x = (int) Math.max(0, Math.floor(jx - junctionRadius) - raster.x);
                        x <= Math.min(width - 1, Math.ceil(jx + junctionRadius) - raster.x); x++) {
                    if (Math.hypot(x + raster.x - jx, y + raster.y - jy) <= junctionRadius) {
                        weight[y * width + x] = 1;
                    }
                }
            }
        }
        float[] density = new float[raster.density.length];
        for (int i = 0; i < density.length; i++) {
            density[i] = (float) ((double) raster.density[i] * weight[i]);
        }
        return raster.withDensity(density, near, raster.heat);
    }

    // Build the "tracking surface": a copy of the raster whose density is locally contrast-normalised.
    static final double MIN_HEAT = 0.02;

    static SlideRaster trackingSurface(SlideRaster raster, double scale, Options options) {
        int radius = (int) Math.max(1, Math.ceil(CONTRAST_RADIUS_METERS / scale));
        int width = raster.width;
        int height = raster.height;
        float[] density = raster.density;
        byte[] coverage = raster.coverage;
        float[] horizontal = new float[density.length];
        float[] signal = new float[density.length];
        int[] queue = new int[Math.max(width, height)];
        for (int y = 0; y < height; y++) {
            if (y % 64 == 0) {
                checkAbort(options);
            }
            int row = y * width;
            int head = 0;
            int tail = 0;
            int next = 0;
            for (int x = 0; x < width; x++) {
                int right = Math.min(width - 1, x + radius);
                while (next <= right) {
                    float v = coverage[row + next] != 0 ? density[row + next] : 0;
                    while (tail > head && (coverage[row + queue[tail - 1]] != 0 ? density[row + queue[tail - 1]] : 0) <= v) {
                        tail--;
                    }
                    queue[tail++] = next++;
                }
                while (queue[head] < x - radius) {
                    head++;
                }
                horizontal[row + x] = coverage[row + queue[head]] != 0 ? density[row + queue[head]] : 0;
            }
        }
        boolean hasSignal = false;
        for (int x = 0; x < width; x++) {
            if (x % 64 == 0) {
                checkAbort(options);
            }
            int head = 0;
            int tail = 0;
            int next = 0;
            for (int y = 0; y < height; y++) {
                int bottom = Math.min(height - 1, y + radius);
                while (next <= bottom) {
                    float v = horizontal[next * width + x];
                    while (tail > head && horizontal[queue[tail - 1] * width + x] <= v) {
                        tail--;
                    }
                    queue[tail++] = next++;
                }
                while (queue[head] < y - radius) {
                    head++;
                }
                int index = y * width + x;
                double value = coverage[index] != 0 ? density[index] : 0;
                signal[index] = (float) (value < MIN_HEAT
                        ? 0
                        : value / Math.max(MIN_HEAT, horizontal[queue[head] * width + x]));
                if (signal[index] >= 0.25) {
                    hasSignal = true;
                }
            }
        }
        if (!hasSignal) {
            throw new NoSignalException("No usable heatmap trail is visible in this area.");
        }
        return raster.withDensity(signal, raster.near, raster.density);
    }

    // Move one original vertex onto the nearest convincing trail point within `radius` pixels.
    static final double ON_TRAIL = 0.1;
    static final double IN_CORE = 0.4;
    static final double HOLD_TURN_DEGREES = 35;
    static final double HOLD_LEG_METERS = 12;
    static final double HOLD_MAX_OFFSET_METERS = 4;

    private static int zone(SlideRaster raster, double[] p) {
        if (raster.near == null) {
            return 0;
        }
        long x = Math.round(p[0]) - raster.x;
        long y = Math.round(p[1]) - raster.y;
        return x >= 0 && y >= 0 && x < raster.width && y < raster.height ? raster.near[(int) (y * raster.width + x)] : 0;
    }

    static double[] snap(SlideRaster raster, double[] point, double radius, double[] normal, double align,
            double hold) {
        boolean startsNear = zone(raster, point) == 1;
        if (normal != null && sampleDensity(raster, point) >= ON_TRAIL) {
            double start = sampleDensity(raster, point);
            double[] peak = {start};
            double up1 = climb(raster, point, normal, 1, start, radius, align, startsNear, peak);
            double up2 = -climb(raster, point, normal, -1, start, radius, align, startsNear, peak);
            List<Double> up = new ArrayList<>();
            if (up1 != 0) {
                up.add(up1);
            }
            if (up2 != 0) {
                up.add(up2);
            }
            // Both sides uphill (between two ridges): the nearer one.
            up.sort((a, b) -> Double.compare(Math.abs(a), Math.abs(b)));
            double offset = up.isEmpty() ? 0 : up.get(0);
            // A held bend stays when on the band and within `hold` pixels of its ridge.
            if (hold != 0 && Math.abs(offset) <= hold && start >= peak[0] * IN_CORE && start >= 0.25) {
                return point.clone();
            }
            double[] centred = {point[0] + normal[0] * offset, point[1] + normal[1] * offset};
            if (sampleDensity(raster, centred) >= 0.25) {
                return centred;
            }
        }
        // Off the trail next to another way: whatever heat a search finds there may be that way's.
        if (zone(raster, point) != 0) {
            return null;
        }
        double[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        if (normal != null) {
            // Off the trail: search across the line too, never along it.
            for (double d = -radius; d <= radius; d += 0.5) {
                double[] p = {point[0] + normal[0] * d, point[1] + normal[1] * d};
                if (!isCovered(raster, p)) {
                    continue;
                }
                double value = sampleDensity(raster, p);
                if (value < 0.25 || (!startsNear && zone(raster, p) == 1)) {
                    continue;
                }
                double score = value - 0.12 * Math.pow(d / radius, 2);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            return best;
        }
        for (int y = (int) Math.floor(point[1] - radius); y <= Math.ceil(point[1] + radius); y++) {
            for (int x = (int) Math.floor(point[0] - radius); x <= Math.ceil(point[0] + radius); x++) {
                double distance = Math.hypot(x - point[0], y - point[1]);
                if (distance > radius || x < raster.x || y < raster.y || x >= raster.x + raster.width
                        || y >= raster.y + raster.height) {
                    continue;
                }
                int index = (y - raster.y) * raster.width + x - raster.x;
                if (raster.coverage[index] == 0) {
                    continue;
                }
                // Filter candidates BEFORE applying the distance penalty.
                if (raster.density[index] < 0.25 || (!startsNear && zone(raster, new double[] {x, y}) == 1)) {
                    continue;
                }
                double score = raster.density[index] - 0.12 * Math.pow(distance / radius, 2);
                if (score > bestScore) {
                    bestScore = score;
                    best = new double[] {x, y};
                }
            }
        }
        return best;
    }

    private static double climb(SlideRaster raster, double[] point, double[] normal, int sign, double start,
            double radius, double align, boolean startsNear, double[] peak) {
        double distance = 0;
        double value = start;
        for (;;) {
            double next = distance + 0.5;
            double[] p = {point[0] + sign * normal[0] * next, point[1] + sign * normal[1] * next};
            double sample = sampleDensity(raster, p);
            if (sample <= value || next > Math.min(radius, align) || (!startsNear && zone(raster, p) == 1)) {
                break;
            }
            distance = next;
            value = sample;
        }
        peak[0] = Math.max(peak[0], value);
        return distance;
    }

    static final double JUMP_BULGE = 0.25;
    static final double VALLEY_DIP = 0.8;

    static boolean crossesValley(SlideRaster raster, List<double[]> points) {
        float[] heat = raster.heat != null ? raster.heat : raster.density;
        double[] values = new double[points.size()];
        for (int k = 0; k < values.length; k++) {
            values[k] = sample(raster, heat, points.get(k));
        }
        double[] after = values.clone();
        for (int k = values.length - 2; k >= 0; k--) {
            after[k] = Math.max(after[k], after[k + 1]);
        }
        double before = 0;
        for (int k = 0; k < values.length; k++) {
            before = Math.max(before, values[k]);
            if (values[k] < VALLEY_DIP * Math.min(before, after[k])) {
                return true;
            }
        }
        return false;
    }

    static final double CORNER_GAP_METERS = 6;
    static final double TIGHT_EDGE_SHARE = 0.1;
    static final String NO_TRAIL_MESSAGE = "No usable heatmap trail found near this line. Draw it closer to the "
            + "trail, or add a point on the trail you want.";

    // Cheapest continuous grid path from start to end inside a corridor of `radius` pixels around the
    // original segment (Dijkstra over the raster cells with 8-connectivity).
    static List<double[]> trace(SlideRaster raster, double[] start, double[] end, double[] originalStart,
            double[] originalEnd, double radius, Options options) {
        if (!isCovered(raster, start) || !isCovered(raster, end)) {
            throw new NoSignalException("Heatmap coverage is incomplete at an endpoint.");
        }
        int minX = (int) Math.max(raster.x,
                Math.floor(min(originalStart[0], originalEnd[0], start[0], end[0]) - radius));
        int minY = (int) Math.max(raster.y,
                Math.floor(min(originalStart[1], originalEnd[1], start[1], end[1]) - radius));
        int maxX = (int) Math.min(raster.x + raster.width - 1,
                Math.ceil(max(originalStart[0], originalEnd[0], start[0], end[0]) + radius));
        int maxY = (int) Math.min(raster.y + raster.height - 1,
                Math.ceil(max(originalStart[1], originalEnd[1], start[1], end[1]) + radius));
        int width = maxX - minX + 1;
        int height = maxY - minY + 1;
        float[] costs = new float[width * height];
        Arrays.fill(costs, Float.POSITIVE_INFINITY);
        int[] parents = new int[width * height];
        Arrays.fill(parents, -1);
        byte[] visited = new byte[width * height];
        float[] cellCosts = new float[width * height];
        Arrays.fill(cellCosts, Float.POSITIVE_INFINITY);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                int i = (y - minY) * width + x - minX;
                int j = (y - raster.y) * raster.width + x - raster.x;
                double distance = distanceToSegment(new double[] {x, y}, originalStart, originalEnd);
                if (raster.coverage[j] == 0 || distance > radius) {
                    continue;
                }
                cellCosts[i] = (float) (0.06 + 8 * Math.pow(1 - raster.density[j], 2)
                        + 0.04 * Math.pow(distance / radius, 2));
                // A trail never runs over itself: make re-using the path traced by earlier sections expensive.
                if (options.avoid != null && options.avoid[j] != 0
                        && Math.hypot(x - start[0], y - start[1]) > options.avoidExclude) {
                    cellCosts[i] = (float) (cellCosts[i] + 3.0);
                }
            }
        }
        int from = (int) ((Math.round(start[1]) - minY) * width + Math.round(start[0]) - minX);
        int to = (int) ((Math.round(end[1]) - minY) * width + Math.round(end[0]) - minX);
        if (!finite(cellCosts, from) || !finite(cellCosts, to)) {
            throw new NoSignalException("The search corridor does not yet reach the snapped trail.");
        }
        MinHeap heap = new MinHeap();
        costs[from] = 0;
        heap.push(from, 0);
        int iterations = 0;
        while (heap.size > 0) {
            int index = heap.pop();
            if (visited[index] != 0) {
                continue;
            }
            visited[index] = 1;
            if (index == to) {
                break;
            }
            if (++iterations % 8192 == 0) {
                checkAbort(options);
            }
            int x = index % width;
            int y = index / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if ((dx == 0 && dy == 0) || x + dx < 0 || y + dy < 0 || x + dx >= width || y + dy >= height) {
                        continue;
                    }
                    int next = index + dy * width + dx;
                    if (visited[next] != 0 || !finite(cellCosts, next)) {
                        continue;
                    }
                    // Do not diagonally jump across holes in tile coverage/the corridor.
                    if (dx != 0 && dy != 0 && (!finite(cellCosts, index + dx) || !finite(cellCosts, index + dy * width))) {
                        continue;
                    }
                    double cost = costs[index] + (Math.hypot(dx, dy) * ((double) cellCosts[index] + cellCosts[next])) / 2;
                    if (cost >= costs[next]) {
                        continue;
                    }
                    costs[next] = (float) cost;
                    parents[next] = index;
                    heap.push(next, cost + 0.06 * Math.hypot(minX + x + dx - end[0], minY + y + dy - end[1]));
                }
            }
        }
        if (visited[to] == 0) {
            throw new NoSignalException("No continuous heatmap coverage along this section.");
        }
        List<double[]> points = new ArrayList<>();
        for (int i = to; i != -1; i = parents[i]) {
            points.add(new double[] {minX + (i % width), minY + i / width});
        }
        Collections.reverse(points);
        points.set(0, start);
        points.set(points.size() - 1, end);
        // Evaluate continuity in locally normalized signal, not absolute brightness.
        boolean[] support = new boolean[points.size()];
        int first = -1;
        int last = -1;
        for (int i = 0; i < support.length; i++) {
            support[i] = sampleDensity(raster, points.get(i)) >= 0.12;
            if (support[i]) {
                if (first == -1) {
                    first = i;
                }
                last = i;
            }
        }
        double leading = 0;
        double trailing = 0;
        double gap = 0;
        double longestGap = 0;
        int dark = 0;
        double supportedLength = 0;
        double totalLength = 0;
        for (int i = 1; i < points.size(); i++) {
            double length = dist(points.get(i), points.get(i - 1));
            totalLength += length;
            if (support[i] && support[i - 1]) {
                supportedLength += length;
            }
        }
        for (int i = 1; i <= first; i++) {
            leading += dist(points.get(i), points.get(i - 1));
        }
        for (int i = last + 1; i < points.size() && i > 0; i++) {
            trailing += dist(points.get(i), points.get(i - 1));
        }
        for (int i = first; i <= last && i >= 0; i++) {
            if (support[i]) {
                gap = 0;
            } else {
                dark++;
                gap += dist(points.get(i), points.get(i - 1));
                longestGap = Math.max(longestGap, gap);
            }
        }
        // A section follows the trail between its anchors: straying sideways much further than the anchors
        // themselves moved means it crossed over to another heat trace and back.
        double sdx = originalEnd[0] - originalStart[0];
        double sdy = originalEnd[1] - originalStart[1];
        double segmentLength = Math.hypot(sdx, sdy);
        double offset = Math.max(sideways(start, originalStart, sdx, sdy, segmentLength),
                sideways(end, originalStart, sdx, sdy, segmentLength));
        double stray = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            stray = Math.max(stray, sideways(p, originalStart, sdx, sdy, segmentLength));
        }
        double bulge = Math.max(JUMP_METERS / options.scale,
                crossesValley(raster, points) ? 0 : JUMP_BULGE * segmentLength);
        // Unless the straight line between the anchors crosses dark ground: the drawing cut a corner.
        double chord = Math.hypot(end[0] - start[0], end[1] - start[1]);
        int darkRun = 0;
        boolean cutsCorner = false;
        for (int s = 0; s <= chord; s++) {
            double[] p = {
                start[0] + ((end[0] - start[0]) * s) / Math.max(1, chord),
                start[1] + ((end[1] - start[1]) * s) / Math.max(1, chord),
            };
            darkRun = sampleDensity(raster, p) < 0.12 ? darkRun + 1 : 0;
            if (darkRun * options.scale >= CORNER_GAP_METERS) {
                cutsCorner = true;
            }
        }
        if (!cutsCorner && stray > offset + bulge) {
            throw new NoSignalException("The heatmap trail here jumps to another trace.");
        }
        double maxGap = 12 / options.scale;
        if (first == -1 || leading > radius + 2 || trailing > radius + 2 || longestGap > maxGap
                || dark > Math.max(3, (last - first + 1) * 0.15)
                || supportedLength < Math.min(20 / options.scale, totalLength * 0.5)) {
            throw new NoSignalException(NO_TRAIL_MESSAGE);
        }
        return points;
    }

    private static double sideways(double[] p, double[] originalStart, double dx, double dy, double length) {
        return length != 0
                ? Math.abs((p[0] - originalStart[0]) * dy - (p[1] - originalStart[1]) * dx) / length
                : Math.hypot(p[0] - originalStart[0], p[1] - originalStart[1]);
    }

    private static boolean finite(float[] array, int index) {
        return !Float.isInfinite(array[index]) && !Float.isNaN(array[index]);
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    private static double min(double... values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(double... values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    // Repeatedly drop the vertex forming the smallest triangle with its neighbours (Visvalingam) until
    // every remaining one exceeds `minArea`.
    static List<double[]> removeSmallTurns(List<double[]> points, double minArea) {
        List<double[]> result = new ArrayList<>(points);
        while (result.size() > 2) {
            int smallest = -1;
            double smallestArea = minArea;
            for (int i = 1; i < result.size() - 1; i++) {
                double[] a = result.get(i - 1);
                double[] b = result.get(i);
                double[] c = result.get(i + 1);
                double value = Math.abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])) / 2;
                if (value < smallestArea) {
                    smallest = i;
                    smallestArea = value;
                }
            }
            if (smallest == -1) {
                break;
            }
            result.remove(smallest);
        }
        return result;
    }

    static List<double[]> simplify(List<double[]> points, double tolerance, SlideRaster raster) {
        boolean[] keep = simplifyKeep(points, tolerance, raster);
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    // Douglas–Peucker with a density guard: which of `points` to keep (the first and last always are).
    static boolean[] simplifyKeep(List<double[]> points, double tolerance, SlideRaster raster) {
        boolean[] keep = new boolean[points.size()];
        if (points.size() <= 2) {
            Arrays.fill(keep, true);
            return keep;
        }
        keep[0] = true;
        keep[points.size() - 1] = true;
        List<int[]> stack = new ArrayList<>();
        stack.add(new int[] {0, points.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.remove(stack.size() - 1);
            int start = range[0];
            int end = range[1];
            int farthest = -1;
            double maxDistance = tolerance;
            for (int i = start + 1; i < end; i++) {
                double distance = distanceToSegment(points.get(i), points.get(start), points.get(end));
                if (distance > maxDistance) {
                    farthest = i;
                    maxDistance = distance;
                }
            }
            if (farthest == -1 && end > start + 1) {
                double[] a = points.get(start);
                double[] b = points.get(end);
                double dx = b[0] - a[0];
                double dy = b[1] - a[1];
                double length = dx * dx + dy * dy;
                // A simplification must not cut across a missing tile corner that the traced path avoided.
                double steps = Math.max(1, Math.ceil(Math.sqrt(length) * 2));
                for (int step = 0; step <= steps; step++) {
                    double t = step / steps;
                    if (!isCovered(raster, new double[] {a[0] + t * dx, a[1] + t * dy})) {
                        farthest = (int) Math.max(start + 1, Math.min(end - 1, Math.round(start + t * (end - start))));
                        break;
                    }
                }
                double worstLoss = 0.06;
                for (int i = start + 1; i < end; i++) {
                    double[] p = points.get(i);
                    double t = length != 0
                            ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length))
                            : 0;
                    double[] replacement = {a[0] + t * dx, a[1] + t * dy};
                    double loss = sampleDensity(raster, p) - sampleDensity(raster, replacement);
                    if (loss > worstLoss) {
                        farthest = i;
                        worstLoss = loss;
                    }
                }
            }
            if (farthest != -1) {
                keep[farthest] = true;
                stack.add(new int[] {start, farthest});
                stack.add(new int[] {farthest, end});
            }
        }
        return keep;
    }

    // A spike: the sections into and out of a vertex run back along each other.
    static final double FOLD_GAP = 0.35;
    static final double FOLD_MIN_METERS = 2;
    static final double FOLD_MAX_METERS = 15;

    /** Point at `distance` pixels along a path, and the index of the segment end. */
    private static final class Along {
        final double[] p;
        final int k;

        Along(double[] p, int k) {
            this.p = p;
            this.k = k;
        }
    }

    private static Along pointAt(List<double[]> path, double distance, boolean backwards) {
        List<double[]> points = path;
        if (backwards) {
            points = new ArrayList<>(path);
            Collections.reverse(points);
        }
        double along = 0;
        for (int k = 1; k < points.size(); k++) {
            double step = dist(points.get(k), points.get(k - 1));
            if (along + step >= distance) {
                double t = step != 0 ? (distance - along) / step : 0;
                double[] p = {
                    points.get(k - 1)[0] + (points.get(k)[0] - points.get(k - 1)[0]) * t,
                    points.get(k - 1)[1] + (points.get(k)[1] - points.get(k - 1)[1]) * t,
                };
                return new Along(p, backwards ? points.size() - 1 - k : k);
            }
            along += step;
        }
        return null;
    }

    // Drop the interior points within `length` pixels along the path from its first point.
    private static List<double[]> approach(List<double[]> points, double length) {
        if (length == 0) {
            return points;
        }
        double along = 0;
        int k = 1;
        for (; k < points.size() - 1; k++) {
            along += dist(points.get(k), points.get(k - 1));
            if (along >= length) {
                break;
            }
        }
        List<double[]> result = new ArrayList<>();
        result.add(points.get(0));
        result.addAll(points.subList(k, points.size()));
        return result;
    }

    private static List<double[]> reversed(List<double[]> points) {
        List<double[]> result = new ArrayList<>(points);
        Collections.reverse(result);
        return result;
    }

    /**
     * Slide an entire line.
     * @param locations the original [lon, lat] vertices of the selected way/range
     * @param raster the merged heatmap raster (createHeatmapRaster)
     * @param inputOptions fixed/limited indices, other ways, cancellation, progress and metre-based knobs
     * @return ordered records: nodeIndex identifies the original vertex a point came from, or null for a
     *         point Slide created. Original vertices appear at most once and in ascending order.
     */
    public static Result slideLine(List<double[]> locations, SlideRaster raster, Options inputOptions) {
        if (locations.size() < 2 || locations.size() > 1000) {
            throw new IllegalArgumentException("Slide works on 2 to 1000 points of a line.");
        }
        for (double[] p : locations) {
            if (p.length != 2 || !Double.isFinite(p[0]) || !Double.isFinite(p[1]) || Math.abs(p[1]) > 85.05112878
                    || Math.abs(p[0]) > 180) {
                throw new IllegalArgumentException("Slide works on 2 to 1000 points of a line.");
            }
        }
        Options options = inputOptions.copy();
        checkAbort(options);
        double scale = metersPerPixel(locations.get(0)[1], raster.zoom);
        double searchMeters = options.searchRadiusMeters != null ? options.searchRadiusMeters : SEARCH_RADIUS_METERS;
        double snapMeters = options.snapRadiusMeters != null ? options.snapRadiusMeters : SNAP_RADIUS_METERS;
        // Explicit radii remain hard limits unless a larger maximum is requested.
        double maxSearchMeters = options.maxSearchRadiusMeters != null ? options.maxSearchRadiusMeters
                : options.searchRadiusMeters == null ? MAX_SEARCH_RADIUS_METERS : searchMeters;
        double maxSnapMeters = options.maxSnapRadiusMeters != null ? options.maxSnapRadiusMeters
                : options.snapRadiusMeters == null ? Math.min(MAX_SNAP_RADIUS_METERS, maxSearchMeters) : snapMeters;
        double radius = searchMeters / scale;
        double snapRadius = snapMeters / scale;
        double tolerance = (options.simplifyMeters != null ? options.simplifyMeters : SIMPLIFY_METERS) / scale;
        double minArea = (options.minTriangleSquareMeters != null ? options.minTriangleSquareMeters
                : MIN_TRIANGLE_SQUARE_METERS) / (scale * scale);
        if (!Double.isFinite(tolerance) || tolerance < 0) {
            throw new IllegalArgumentException("Invalid Slide simplification tolerance.");
        }
        if (!Double.isFinite(searchMeters) || !Double.isFinite(snapMeters) || !Double.isFinite(maxSearchMeters)
                || !Double.isFinite(maxSnapMeters) || !(radius > 0) || !(snapRadius > 0) || snapRadius > radius
                || maxSearchMeters < searchMeters || maxSnapMeters < snapMeters || maxSnapMeters > maxSearchMeters) {
            throw new IllegalArgumentException("Invalid Slide search radius.");
        }
        options.scale = scale;
        // 1) Discovery surface: locally contrast-normalised density, without the heat of other ways.
        SlideRaster own = options.otherWays != null && !options.otherWays.isEmpty()
                ? maskOtherWays(raster, options.otherWays, locations, scale)
                : raster;
        SlideRaster tracking = trackingSurface(own, scale, options);
        int n = locations.size();
        List<double[]> originals = new ArrayList<>();
        for (double[] loc : locations) {
            originals.add(project(loc, raster.zoom));
        }
        // Fixed vertices never move. Limited ones (junctions, way ends) may snap, but only within the normal
        // radius; with no trail there they stay fixed.
        Set<Integer> fixed = new LinkedHashSet<>(options.fixedIndices);
        Set<Integer> limited = new LinkedHashSet<>(options.limitedIndices);
        List<Double> snapSearches = searchRadii(snapRadius, maxSnapMeters / scale);
        double align = (options.alignMeters != null ? options.alignMeters : ALIGN_METERS) / scale;
        // Bends of the drawing: +1 or -1 by turn side.
        int[] bends = new int[n];
        for (int i = 1; i < n - 1; i++) {
            double[] p = originals.get(i);
            double[] a = originals.get(i - 1);
            double[] b = originals.get(i + 1);
            double[] u = {p[0] - a[0], p[1] - a[1]};
            double[] v = {b[0] - p[0], b[1] - p[1]};
            double length = Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]);
            boolean sharp = length > 0
                    && Math.min(Math.hypot(u[0], u[1]), Math.hypot(v[0], v[1])) * scale < HOLD_LEG_METERS
                    && (Math.acos(Math.max(-1, Math.min(1, (u[0] * v[0] + u[1] * v[1]) / length))) * 180) / Math.PI
                    > HOLD_TURN_DEGREES;
            bends[i] = sharp ? (int) Math.signum(u[0] * v[1] - u[1] * v[0]) : 0;
        }
        // Two opposite bends joined by one short leg between long ones are a jog of the drawing.
        boolean[] holds = new boolean[n];
        for (int i = 0; i < n; i++) {
            int side = bends[i];
            if (!options.holdBends || side == 0) {
                continue;
            }
            for (int j : new int[] {i - 2, i - 1, i + 1, i + 2}) {
                if (j >= 0 && j < n && bends[j] != 0 && bends[j] == -side
                        && !jog(originals, scale, Math.min(i, j), Math.max(i, j))) {
                    holds[i] = true;
                    break;
                }
            }
        }
        // Unit normal of the drawn line at each vertex (from its neighbours).
        List<double[]> normals = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] a = originals.get(Math.max(0, i - 1));
            double[] b = originals.get(Math.min(n - 1, i + 1));
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            normals.add(length != 0 ? new double[] {-(b[1] - a[1]) / length, (b[0] - a[0]) / length} : null);
        }
        // 2) Snap every vertex to a trail point ("anchor").
        List<double[]> anchors = new ArrayList<>();
        Set<Integer> unsnapped = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            checkAbort(options);
            double[] anchor = fixed.contains(i) ? originals.get(i) : null;
            List<Double> radii = limited.contains(i) ? Collections.singletonList(snapRadius) : snapSearches;
            for (double r : radii) {
                if (anchor != null) {
                    break;
                }
                anchor = snap(tracking, originals.get(i), r, normals.get(i), align,
                        holds[i] ? HOLD_MAX_OFFSET_METERS / scale : 0);
            }
            // The limit is measured from where the vertex was before ANY pass.
            double[] origin = options.limitedOrigins != null ? options.limitedOrigins.get(i) : null;
            if (anchor != null && limited.contains(i) && origin != null) {
                double[] o = project(origin, raster.zoom);
                if (Math.hypot(o[0] - anchor[0], o[1] - anchor[1]) > snapRadius) {
                    anchor = null;
                }
            }
            if (anchor == null && limited.contains(i)) {
                anchor = originals.get(i);
                fixed.add(i);
            } else if (anchor == null) {
                anchor = originals.get(i);
                unsnapped.add(i);
            }
            anchors.add(anchor);
        }
        // A closed line starts and ends on the same node: the last copies the first.
        int last = n - 1;
        boolean closed = last > 1 && locations.get(0)[0] == locations.get(last)[0]
                && locations.get(0)[1] == locations.get(last)[1];
        if (closed) {
            if (fixed.contains(0) || fixed.contains(last)) {
                fixed.add(0);
                fixed.add(last);
                anchors.set(0, originals.get(0));
            }
            if (unsnapped.contains(0)) {
                unsnapped.add(last);
            }
            anchors.set(last, anchors.get(0));
        }
        // 3) Trace the ridge from anchor to anchor, segment by segment.
        SectionTracer tracer = new SectionTracer(tracking, originals, anchors, options, scale, radius,
                maxSearchMeters);
        List<List<double[]>> paths = tracer.paths;
        paths.add(null);
        for (int i = 1; i < n; i++) {
            paths.add(options.untracedSections.contains(i) ? null : tracer.traceSection(i));
            if (paths.get(i) != null) {
                tracer.markAvoid(paths.get(i));
            }
            if (options.onProgress != null) {
                options.onProgress.accept((double) i / (n - 1));
            }
        }
        for (boolean changed = true; changed;) {
            changed = false;
            for (int i = 1; i < n; i++) {
                if (paths.get(i) != null) {
                    continue;
                }
                for (int v : new int[] {i - 1, i}) {
                    if (fixed.contains(v) || unsnapped.contains(v)) {
                        continue;
                    }
                    for (int w : twins(closed, v, last)) {
                        unsnapped.add(w);
                        anchors.set(w, originals.get(w));
                        if (w > 0 && paths.get(w) != null) {
                            paths.set(w, tracer.retraceSection(w));
                        }
                        if (w + 1 < n && paths.get(w + 1) != null) {
                            paths.set(w + 1, tracer.retraceSection(w + 1));
                        }
                    }
                    changed = true;
                }
            }
        }
        // A vertex next to a section left as drawn is where the heat ends: if it was found off the trail,
        // it stays as drawn.
        for (boolean changed = true; changed;) {
            changed = false;
            for (int i = 1; i < n; i++) {
                if (paths.get(i) != null && !unsnapped.contains(i - 1) && !unsnapped.contains(i)) {
                    continue;
                }
                for (int v : new int[] {i - 1, i}) {
                    if (fixed.contains(v) || unsnapped.contains(v)) {
                        continue;
                    }
                    double dx = anchors.get(v)[0] - originals.get(v)[0];
                    double dy = anchors.get(v)[1] - originals.get(v)[1];
                    if (Math.hypot(dx, dy) <= align) {
                        continue;
                    }
                    for (int w : twins(closed, v, last)) {
                        unsnapped.add(w);
                        anchors.set(w, originals.get(w));
                    }
                    changed = true;
                }
            }
        }
        // A free vertex snapped off the trail makes a spike: cut the folded part off both sections.
        for (int i = 1; i < n - 1; i++) {
            if (fixed.contains(i) || unsnapped.contains(i) || holds[i] || paths.get(i) == null
                    || paths.get(i + 1) == null) {
                continue;
            }
            Along foldA = null;
            Along foldB = null;
            double foldD = 0;
            for (double d = 0.5; d <= FOLD_MAX_METERS / scale; d += 0.5) {
                Along a = pointAt(paths.get(i), d, true);
                Along b = pointAt(paths.get(i + 1), d, false);
                if (a == null || b == null) {
                    break;
                }
                if (Math.hypot(a.p[0] - b.p[0], a.p[1] - b.p[1]) > FOLD_GAP * d) {
                    break;
                }
                foldA = a;
                foldB = b;
                foldD = d;
            }
            if (foldA == null || foldD * scale < FOLD_MIN_METERS) {
                continue;
            }
            double[] middle = {(foldA.p[0] + foldB.p[0]) / 2, (foldA.p[1] + foldB.p[1]) / 2};
            List<double[]> before = new ArrayList<>(paths.get(i).subList(0, foldA.k));
            before.add(middle);
            List<double[]> after = new ArrayList<>();
            after.add(middle);
            after.addAll(paths.get(i + 1).subList(foldB.k, paths.get(i + 1).size()));
            paths.set(i, before);
            paths.set(i + 1, after);
            anchors.set(i, middle);
        }
        // A section touching a vertex left off-trail is drawn straight too.
        boolean[] traced = new boolean[n];
        for (int i = 0; i < n; i++) {
            traced[i] = paths.get(i) != null && !unsnapped.contains(i - 1) && !unsnapped.contains(i);
        }
        Result result = new Result();
        result.add(new Record(locate(0, fixed, unsnapped, traced, locations, anchors, raster.zoom, n), 0));
        int skipped = 0;
        for (int i = 1; i < n; i++) {
            if (!traced[i]) {
                skipped++;
            }
        }
        result.skipped = skipped;
        if (skipped == n - 1) {
            throw new NoSignalException(NO_TRAIL_MESSAGE);
        }
        for (int i = 1; i < n; i++) {
            // Approach on the traced pixels, before simplifying.
            List<double[]> path = traced[i] ? paths.get(i) : new ArrayList<>();
            if (path.size() > 2) {
                path = approach(path, approachLength(i - 1, fixed, tracking, originals, snapRadius, normals, align));
                path = reversed(approach(reversed(path),
                        approachLength(i, fixed, tracking, originals, snapRadius, normals, align)));
            }
            List<double[]> reduced = traced[i]
                    ? removeSmallTurns(simplify(path, tolerance, tracking), minArea)
                    : new ArrayList<>();
            // The path's first and last pixel are the anchors themselves: skip them here.
            for (int j = 1; j < reduced.size() - 1; j++) {
                result.add(new Record(unproject(reduced.get(j), raster.zoom), null));
            }
            Record record = new Record(locate(i, fixed, unsnapped, traced, locations, anchors, raster.zoom, n), i);
            record.untraced = !traced[i];
            result.add(record);
        }
        checkAbort(options);
        // The trail decides the points, not the drawing: simplify the whole slid line again.
        boolean[] keepVertex = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean held = holds[i] && anchors.get(i)[0] == originals.get(i)[0]
                    && anchors.get(i)[1] == originals.get(i)[1];
            keepVertex[i] = fixed.contains(i) || unsnapped.contains(i) || held || !traced[i]
                    || (i < last && !traced[i + 1]);
        }
        List<double[]> pixels = new ArrayList<>();
        for (Record record : result) {
            pixels.add(project(record.loc, raster.zoom));
        }
        boolean[] keep = new boolean[result.size()];
        int from = 0;
        for (int k = 1; k < result.size(); k++) {
            Integer i = result.get(k).nodeIndex;
            if (k < result.size() - 1 && !(i != null && keepVertex[i])) {
                continue;
            }
            boolean[] span = simplifyKeep(pixels.subList(from, k + 1), tolerance, tracking);
            for (int j = 0; j < span.length; j++) {
                keep[from + j] |= span[j];
            }
            from = k;
        }
        Result simplified = new Result();
        for (int k = 0; k < result.size(); k++) {
            if (!keep[k]) {
                continue;
            }
            Record record = result.get(k);
            if (record.nodeIndex != null && keepVertex[record.nodeIndex]) {
                record = record.copy();
                record.drawn = true;
            }
            simplified.add(record);
        }
        simplified.skipped = result.skipped;
        return simplified;
    }

    private static boolean jog(List<double[]> originals, double scale, int a, int b) {
        return b == a + 1 && a > 0 && b < originals.size() - 1 && leg(originals, scale, a - 1) >= HOLD_LEG_METERS
                && leg(originals, scale, b) >= HOLD_LEG_METERS;
    }

    private static double leg(List<double[]> originals, double scale, int a) {
        return dist(originals.get(a + 1), originals.get(a)) * scale;
    }

    private static int[] twins(boolean closed, int v, int last) {
        int twin = closed && v == 0 ? last : closed && v == last ? 0 : -1;
        return twin < 0 ? new int[] {v} : new int[] {v, twin};
    }

    // A fixed vertex may sit off the trail center: join the ridge gradually, over approachFactor × its offset.
    private static double approachLength(int v, Set<Integer> fixed, SlideRaster tracking, List<double[]> originals,
            double snapRadius, List<double[]> normals, double align) {
        if (!fixed.contains(v)) {
            return 0;
        }
        double[] center = snap(tracking, originals.get(v), snapRadius, normals.get(v), align, 0);
        if (center == null) {
            return 0;
        }
        double offset = Math.hypot(center[0] - originals.get(v)[0], center[1] - originals.get(v)[1]);
        return offset > 1 ? APPROACH_FACTOR * offset : 0;
    }

    // A vertex between two sections left as drawn stays as drawn too.
    private static double[] locate(int i, Set<Integer> fixed, Set<Integer> unsnapped, boolean[] traced,
            List<double[]> locations, List<double[]> anchors, int zoom, int n) {
        boolean nextTraced = i + 1 < n && traced[i + 1];
        return fixed.contains(i) || unsnapped.contains(i) || (!traced[i] && !nextTraced)
                ? locations.get(i).clone()
                : unproject(anchors.get(i), zoom);
    }

    /** Traces the sections of a line, avoiding the paths traced by the earlier sections. */
    private static final class SectionTracer {
        final SlideRaster tracking;
        final List<double[]> originals;
        final List<double[]> anchors;
        final Options options;
        final double scale;
        final double radius;
        final double maxSearchMeters;
        final int avoidRadius;
        final double avoidExclude;
        final List<List<double[]>> paths = new ArrayList<>();
        byte[] avoid;

        SectionTracer(SlideRaster tracking, List<double[]> originals, List<double[]> anchors, Options options,
                double scale, double radius, double maxSearchMeters) {
            this.tracking = tracking;
            this.originals = originals;
            this.anchors = anchors;
            this.options = options;
            this.scale = scale;
            this.radius = radius;
            this.maxSearchMeters = maxSearchMeters;
            this.avoidRadius = (int) Math.ceil(AVOID_METERS / scale);
            this.avoidExclude = (2 * AVOID_METERS) / scale;
            this.avoid = new byte[tracking.width * tracking.height];
        }

        void markAvoid(List<double[]> path) {
            for (double[] p : path) {
                for (int dy = -avoidRadius; dy <= avoidRadius; dy++) {
                    for (int dx = -avoidRadius; dx <= avoidRadius; dx++) {
                        long x = Math.round(p[0]) + dx - tracking.x;
                        long y = Math.round(p[1]) + dy - tracking.y;
                        if (dx * dx + dy * dy > avoidRadius * avoidRadius) {
                            continue;
                        }
                        if (x >= 0 && y >= 0 && x < tracking.width && y < tracking.height) {
                            avoid[(int) (y * tracking.width + x)] = 1;
                        }
                    }
                }
            }
        }

        List<double[]> retraceSection(int i) {
            avoid = new byte[tracking.width * tracking.height];
            for (int k = 1; k < i; k++) {
                if (paths.get(k) != null) {
                    markAvoid(paths.get(k));
                }
            }
            return traceSection(i);
        }

        List<double[]> traceSection(int i) {
            Options traceOptions = options.copy();
            traceOptions.avoid = avoid;
            traceOptions.avoidExclude = avoidExclude;
            // First a tight corridor, jumpMeters beyond the anchors' own offset.
            double[] a = originals.get(i - 1);
            double[] b = originals.get(i);
            double tight = Math.max(distanceToSegment(anchors.get(i - 1), a, b), distanceToSegment(anchors.get(i), a, b))
                    + JUMP_METERS / scale;
            try {
                List<double[]> path = trace(tracking, anchors.get(i - 1), anchors.get(i), a, b, tight, traceOptions);
                int edge = 0;
                for (double[] p : path) {
                    if (distanceToSegment(p, a, b) > tight - 1.5) {
                        edge++;
                    }
                }
                if (edge <= path.size() * TIGHT_EDGE_SHARE) {
                    return path;
                }
            } catch (NoSignalException e) {
                // Widen below
            }
            // Then a narrow corridor (precise on hairpins), widened only when needed.
            for (double r : searchRadii(radius, maxSearchMeters / scale)) {
                checkAbort(options);
                try {
                    return trace(tracking, anchors.get(i - 1), anchors.get(i), originals.get(i - 1), originals.get(i),
                            r, traceOptions);
                } catch (NoSignalException e) {
                    // Try a wider corridor
                }
            }
            return null;
        }
    }

    // Slide twice: a second pass over the first result (now densely on the trail) smooths most remaining kinks.
    static final double SECOND_PASS_ALIGN_METERS = 2;
    static final double JUNCTION_STUB_METERS = 25;

    static Set<Integer> junctionStubs(Result first, List<double[]> locations, Set<Integer> fixed,
            List<List<double[]>> otherWays) {
        Set<Integer> stubs = new LinkedHashSet<>();
        Map<Integer, Record> record = new HashMap<>();
        for (Record r : first) {
            if (r.nodeIndex != null) {
                record.put(r.nodeIndex, r);
            }
        }
        for (int j : fixed) {
            if (!isJunction(locations.get(j), otherWays)) {
                continue;
            }
            for (int step : new int[] {-1, 1}) {
                List<Integer> run = new ArrayList<>();
                int k = j + step;
                for (; k >= 0 && k < locations.size(); k += step) {
                    Record r = record.get(k);
                    // The section between k and the previous vertex was left as drawn.
                    Record section = record.get(Math.max(k, k - step));
                    if (r == null || !r.drawn || fixed.contains(k) || section == null || !section.untraced) {
                        break;
                    }
                    if (meters(locations.get(j), locations.get(k)) > JUNCTION_STUB_METERS) {
                        break;
                    }
                    run.add(k);
                }
                // ...and the line goes on along its trail after them.
                Record next = record.get(k);
                if (!run.isEmpty() && next != null && !next.drawn) {
                    stubs.addAll(run);
                }
            }
        }
        return stubs;
    }

    private static double meters(double[] a, double[] b) {
        return Math.hypot((b[0] - a[0]) * Math.cos((a[1] * Math.PI) / 180), b[1] - a[1]) * 111320;
    }

    private static boolean isJunction(double[] loc, List<List<double[]>> otherWays) {
        for (List<double[]> way : otherWays) {
            for (double[] o : way) {
                if (o[0] == loc[0] && o[1] == loc[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    public static Result slideLineTwice(List<double[]> locations, SlideRaster raster, Options options) {
        DoubleConsumer progress = options.onProgress;
        Options firstOptions = options.copy();
        firstOptions.onProgress = progress == null ? null : v -> progress.accept(v / 2);
        Result first = slideLine(locations, raster, firstOptions);
        if (first.size() > 1000) {
            return first;
        }
        Set<Integer> fixed = options.fixedIndices;
        Set<Integer> limited = options.limitedIndices;
        // Drawn points next to a junction that found no trail, while the line further on is on it: drop them
        // and slide again, so the trail is traced into the junction.
        Set<Integer> stubs = junctionStubs(first, locations, fixed, options.otherWays != null ? options.otherWays
                : Collections.emptyList());
        if (!stubs.isEmpty()) {
            List<Integer> kept = new ArrayList<>();
            for (int i = 0; i < locations.size(); i++) {
                if (!stubs.contains(i)) {
                    kept.add(i);
                }
            }
            Map<Integer, Integer> position = new HashMap<>();
            for (int k = 0; k < kept.size(); k++) {
                position.put(kept.get(k), k);
            }
            List<double[]> keptLocations = new ArrayList<>();
            for (int i : kept) {
                keptLocations.add(locations.get(i));
            }
            Options againOptions = firstOptions.copy();
            againOptions.fixedIndices = remap(options.fixedIndices, position);
            againOptions.limitedIndices = remap(options.limitedIndices, position);
            Result again = slideLine(keptLocations, raster, againOptions);
            Result remapped = new Result();
            for (Record record : again) {
                Record r = record.copy();
                r.nodeIndex = record.nodeIndex == null ? null : kept.get(record.nodeIndex);
                remapped.add(r);
            }
            remapped.skipped = again.skipped;
            first = remapped;
        }
        // What the first pass left as drawn stays as drawn: its vertices are fixed and its sections are not
        // traced again.
        Options secondOptions = options.copy();
        secondOptions.fixedIndices = new LinkedHashSet<>();
        secondOptions.untracedSections = new LinkedHashSet<>();
        secondOptions.limitedIndices = new LinkedHashSet<>();
        secondOptions.limitedOrigins = new LinkedHashMap<>();
        List<double[]> firstLocations = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) {
            Record record = first.get(i);
            firstLocations.add(record.loc);
            if ((record.nodeIndex != null && fixed.contains(record.nodeIndex)) || record.drawn) {
                secondOptions.fixedIndices.add(i);
            }
            if (record.untraced) {
                secondOptions.untracedSections.add(i);
            }
            if (record.nodeIndex != null && limited.contains(record.nodeIndex)) {
                secondOptions.limitedIndices.add(i);
                secondOptions.limitedOrigins.put(i, locations.get(record.nodeIndex));
            }
        }
        secondOptions.holdBends = false;
        // Its vertices are already on the trail: re-centre them a little, never climb to another part of the band.
        secondOptions.alignMeters = SECOND_PASS_ALIGN_METERS;
        secondOptions.onProgress = progress == null ? null : v -> progress.accept(0.5 + v / 2);
        Result second = slideLine(firstLocations, raster, secondOptions);
        List<Record> mapped = new ArrayList<>();
        for (Record record : second) {
            Record r = new Record(record.loc, record.nodeIndex == null ? null : first.get(record.nodeIndex).nodeIndex);
            r.drawn = record.drawn;
            mapped.add(r);
        }
        List<Record> smoothed = new ArrayList<>();
        for (Record record : smoothLine(mapped, raster)) {
            smoothed.add(new Record(record.loc, record.nodeIndex));
        }
        Result stable = keepStable(locations, smoothed);
        stable.skipped = first.skipped;
        return stable;
    }

    private static Set<Integer> remap(Set<Integer> indices, Map<Integer, Integer> position) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int i : indices) {
            if (position.containsKey(i)) {
                result.add(position.get(i));
            }
        }
        return result;
    }

    // Where the slid line stays within STABLE_METERS of the line as it was, keep the points as they were.
    static final double STABLE_METERS = 2;

    private static final class Nearest {
        final double d;
        final double s;

        Nearest(double d, double s) {
            this.d = d;
            this.s = s;
        }
    }

    private static Nearest nearest(double[] p, List<double[]> line, double[] cumulative) {
        Nearest best = new Nearest(Double.POSITIVE_INFINITY, 0);
        for (int k = 1; k < line.size(); k++) {
            double[] a = line.get(k - 1);
            double[] b = line.get(k);
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double length = dx * dx + dy * dy;
            double t = length != 0
                    ? Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length))
                    : 0;
            double d = Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
            if (d < best.d) {
                best = new Nearest(d, cumulative != null ? cumulative[k - 1] + t * Math.sqrt(length) : 0);
            }
        }
        return best;
    }

    static Result keepStable(List<double[]> locations, List<Record> result) {
        if (locations.size() < 2 || result.size() < 2) {
            return new Result(result);
        }
        double lat0 = (locations.get(0)[1] * Math.PI) / 180;
        List<double[]> pLine = new ArrayList<>();
        for (double[] loc : locations) {
            pLine.add(new double[] {loc[0] * 111320 * Math.cos(lat0), loc[1] * 110540});
        }
        List<double[]> qLine = new ArrayList<>();
        for (Record r : result) {
            qLine.add(new double[] {r.loc[0] * 111320 * Math.cos(lat0), r.loc[1] * 110540});
        }
        double[] along = new double[pLine.size()];
        for (int k = 1; k < pLine.size(); k++) {
            along[k] = along[k - 1] + dist(pLine.get(k), pLine.get(k - 1));
        }
        Nearest[] onP = new Nearest[qLine.size()];
        for (int k = 0; k < qLine.size(); k++) {
            onP[k] = nearest(qLine.get(k), pLine, along);
        }
        boolean[] pMoved = new boolean[pLine.size()];
        for (int k = 0; k < pLine.size(); k++) {
            pMoved[k] = nearest(pLine.get(k), qLine, null).d > STABLE_METERS;
        }
        List<Record> out = new ArrayList<>();
        boolean changed = false;
        for (int a = 0; a < qLine.size();) {
            boolean close = onP[a].d <= STABLE_METERS;
            int b = a;
            while (b + 1 < qLine.size() && (onP[b + 1].d <= STABLE_METERS) == close) {
                b++;
            }
            List<Integer> old = new ArrayList<>();
            if (close) {
                for (int i = 0; i < pLine.size(); i++) {
                    if (along[i] >= onP[a].s - 1e-6 && along[i] <= onP[b].s + 1e-6) {
                        old.add(i);
                    }
                }
            }
            boolean anyMoved = false;
            for (int i : old) {
                anyMoved |= pMoved[i];
            }
            boolean keepOld = close && !old.isEmpty() && !anyMoved
                    && Math.abs(old.size() - (b - a + 1)) <= Math.max(2, 0.25 * Math.max(old.size(), b - a + 1));
            if (keepOld) {
                for (int i : old) {
                    out.add(new Record(locations.get(i).clone(), i));
                }
            } else {
                changed = true;
                for (int k = a; k <= b; k++) {
                    out.add(new Record(result.get(k).loc.clone(), result.get(k).nodeIndex));
                }
            }
            a = b + 1;
        }
        Result stable = new Result();
        if (!changed) {
            for (int i = 0; i < locations.size(); i++) {
                stable.add(new Record(locations.get(i).clone(), i));
            }
            return stable;
        }
        // Each original vertex at most once, in order.
        int previous = -1;
        for (int k = 0; k < out.size(); k++) {
            Record point = out.get(k);
            if (point.nodeIndex == null) {
                stable.add(point);
                continue;
            }
            if (point.nodeIndex <= previous) {
                // The same old point from both sides of a run boundary: keep one.
                Record prev = k > 0 ? out.get(k - 1) : null;
                if (prev != null && point.nodeIndex.equals(prev.nodeIndex)) {
                    continue;
                }
                stable.add(new Record(point.loc, null));
                continue;
            }
            previous = point.nodeIndex;
            stable.add(point);
        }
        return stable;
    }

    // Smooth the slid line's turns: resample every SMOOTH_STEP_METERS and smooth by SMOOTH_PASSES rounds.
    static final double SMOOTH_STEP_METERS = 1.5;
    static final int SMOOTH_PASSES = 24;
    static final double SMOOTH_TOLERANCE_METERS = 0.4;
    static final double SMOOTH_MIN_HEAT = 0.5;

    static List<Record> smoothLine(List<Record> records, SlideRaster raster) {
        int zoom = raster.zoom;
        double scale = metersPerPixel(records.get(0).loc[1], zoom);
        double step = SMOOTH_STEP_METERS / scale;
        boolean[] pinned = new boolean[records.size()];
        for (int k = 0; k < records.size(); k++) {
            pinned[k] = k == 0 || k == records.size() - 1 || records.get(k).drawn;
        }
        List<Record> result = new ArrayList<>();
        result.add(records.get(0));
        for (int from = 0; from < records.size() - 1;) {
            int to = from + 1;
            while (!pinned[to]) {
                to++;
            }
            List<Record> run = records.subList(from, to + 1);
            List<Record> smoothed = smoothRun(run, raster, step, scale);
            result.addAll(smoothed.subList(1, smoothed.size()));
            from = to;
        }
        return result;
    }

    static List<Record> smoothRun(List<Record> run, SlideRaster raster, double step, double scale) {
        if (run.size() < 3) {
            return run;
        }
        int zoom = raster.zoom;
        List<double[]> points = new ArrayList<>();
        for (Record record : run) {
            points.add(project(record.loc, zoom));
        }
        // Resample along the line; remember where each original point falls.
        List<double[]> samples = new ArrayList<>();
        samples.add(points.get(0));
        List<Integer> at = new ArrayList<>();
        at.add(0);
        for (int k = 1; k < points.size(); k++) {
            double[] a = points.get(k - 1);
            double[] b = points.get(k);
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            long count = Math.max(1, Math.round(length / step));
            for (long s = 1; s <= count; s++) {
                samples.add(new double[] {a[0] + ((b[0] - a[0]) * s) / count, a[1] + ((b[1] - a[1]) * s) / count});
            }
            at.add(samples.size() - 1);
        }
        double[] heat = new double[samples.size()];
        for (int i = 0; i < heat.length; i++) {
            heat[i] = sampleDensity(raster, samples.get(i)) * SMOOTH_MIN_HEAT;
        }
        List<double[]> smooth = null;
        for (double passes = SMOOTH_PASSES; passes >= SMOOTH_PASSES / 8.0 && smooth == null; passes /= 2) {
            List<double[]> current = new ArrayList<>();
            for (double[] p : samples) {
                current.add(p.clone());
            }
            for (int pass = 0; pass < passes; pass++) {
                List<double[]> next = new ArrayList<>();
                for (double[] p : current) {
                    next.add(p.clone());
                }
                for (int i = 1; i < current.size() - 1; i++) {
                    for (int axis = 0; axis < 2; axis++) {
                        next.get(i)[axis] = current.get(i)[axis] / 2
                                + (current.get(i - 1)[axis] + current.get(i + 1)[axis]) / 4;
                    }
                }
                current = next;
            }
            boolean onTrail = true;
            for (int i = 0; i < current.size(); i++) {
                if (sampleDensity(raster, current.get(i)) < heat[i]) {
                    onTrail = false;
                    break;
                }
            }
            if (onTrail) {
                smooth = current;
            }
        }
        if (smooth == null) {
            return run;
        }
        boolean[] keep = simplifyKeep(smooth, SMOOTH_TOLERANCE_METERS / scale, raster);
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < keep.length; i++) {
            if (keep[i]) {
                kept.add(i);
            }
        }
        // Reuse the original vertices (their OSM nodes) for the nearest kept points, in order.
        Map<Integer, Integer> owner = new HashMap<>();
        owner.put(0, run.get(0).nodeIndex);
        owner.put(kept.get(kept.size() - 1), run.get(run.size() - 1).nodeIndex);
        int next = 1;
        for (int k = 1; k < run.size() - 1; k++) {
            if (run.get(k).nodeIndex == null) {
                continue;
            }
            int best = -1;
            for (int j = next; j < kept.size() - 1; j++) {
                if (best == -1 || Math.abs(kept.get(j) - at.get(k)) < Math.abs(kept.get(best) - at.get(k))) {
                    best = j;
                }
            }
            if (best == -1) {
                break;
            }
            if (!owner.containsKey(kept.get(best))) {
                owner.put(kept.get(best), run.get(k).nodeIndex);
            }
            next = best + 1;
        }
        List<Record> result = new ArrayList<>();
        for (int j = 0; j < kept.size(); j++) {
            int i = kept.get(j);
            double[] loc = j == 0 ? run.get(0).loc
                    : j == kept.size() - 1 ? run.get(run.size() - 1).loc : unproject(smooth.get(i), zoom);
            Record record = new Record(loc, owner.get(i));
            record.drawn = j == 0 ? run.get(0).drawn : j == kept.size() - 1 && run.get(run.size() - 1).drawn;
            result.add(record);
        }
        return result;
    }
}
