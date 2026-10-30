// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the centre lines of a heatmap trace as pixel polylines.
 * <p>
 * Pipeline: seed search, flood fill of the pixels above a threshold relative to the trace intensity
 * (jumping over small gaps), extension of the free ends over larger gaps in the direction of the trace,
 * dilation and hole filling, Zhang-Suen thinning, conversion of the skeleton to polylines, iterative spur
 * pruning, merging of the branches continuing each other and Douglas-Peucker simplification.
 * Images are indexed as {@code [y][x]}; points use {@code x} = column and {@code y} = row.
 */
public final class TraceExtractor {

    private static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};   // N, NE, E, SE, S, SW, W, NW
    private static final int[] DY = {-1, -1, 0, 1, 1, 1, 0, -1};
    /** Maximum number of extension rounds of the free ends. */
    private static final int MAX_EXTENSIONS = 50;
    /** Half-angle (in degrees) of the cone searched in front of a free end. */
    private static final int JUMP_HALF_ANGLE = 30;
    /** Dangling branches shorter than this part of the longest line are removed. */
    private static final double MIN_BRANCH_RATIO = 0.1;

    /** Tuning parameters of the extraction. */
    public static final class Params {
        /** Absolute minimum intensity (0-255) of a trace pixel. */
        public int threshold = 10;
        /** Minimum intensity of a trace pixel, relative to the hottest pixel near the click. */
        public double relativeThreshold = 0.2;
        /** Maximum gap (in pixels) bridged in any direction between two parts of a trace. */
        public int maxGap = 2;
        /** Maximum gap (in pixels) bridged at a free end, in the direction of the trace. */
        public int maxJump = 12;
        /** Minimum length (in pixels) of a branch ending in the void, at least twice the trace width. */
        public int spurLength = 10;
        /** Maximum area (in pixels) of the holes filled in the trace. */
        public int maxHoleArea = 60;
        /** Maximum deviation (in degrees) between two branches considered as the same way at a junction. */
        public double maxTurnAngle = 50;
        /** Douglas-Peucker tolerance (in pixels). */
        public double simplifyTolerance = 1.0;
        /** Radius (in pixels) around the click where the hottest pixel is searched. */
        public int seedSearchRadius = 10;
        /** Also return the traces branching from the clicked one. */
        public boolean branches;
        /** Output: threshold used by the last extraction. */
        public int effectiveThreshold;
    }

    private TraceExtractor() {
    }

    /**
     * Extracts the trace under the given pixel.
     * @param intensity heatmap intensities (0-255)
     * @param blocked pixels to ignore (existing ways), may be {@code null}
     * @param x clicked column
     * @param y clicked row
     * @param params tuning parameters
     * @return the simplified polylines, empty if no trace was found
     */
    public static List<List<Point>> extract(int[][] intensity, boolean[][] blocked, int x, int y, Params params) {
        params.effectiveThreshold = params.threshold;
        Point seed = findSeed(intensity, blocked, x, y, params.seedSearchRadius, params.threshold);
        if (seed == null) {
            return new ArrayList<>();
        }
        // The threshold follows the popularity of the clicked trace
        int threshold = Math.max(params.threshold,
                (int) Math.round(params.relativeThreshold * intensity[seed.y][seed.x]));
        params.effectiveThreshold = threshold;
        boolean[][] region = new boolean[intensity.length][intensity[0].length];
        grow(region, intensity, blocked, seed, threshold, params.maxGap);
        List<List<Point>> lines = centreLines(region, blocked, params);

        // Follow the trace over the larger gaps of the unpopular traces
        for (int round = 0; round < MAX_EXTENSIONS && params.maxJump > 0; round++) {
            boolean extended = false;
            Map<Point, Integer> degree = endpointDegrees(lines);
            for (List<Point> line : lines) {
                for (boolean fromStart : new boolean[] {true, false}) {
                    Point end = fromStart ? line.get(0) : line.get(line.size() - 1);
                    if (degree.get(end) != 1) {
                        continue;
                    }
                    double[] inward = direction(line, fromStart);
                    Point next = findContinuation(intensity, blocked, region, end,
                            new double[] {-inward[0], -inward[1]}, threshold, params.maxJump);
                    if (next != null) {
                        fillGap(region, blocked, intensity, threshold, end, next.x, next.y);
                        grow(region, intensity, blocked, next, threshold, params.maxGap);
                        extended = true;
                    }
                }
            }
            if (!extended) {
                break;
            }
            lines = centreLines(region, blocked, params);
        }
        lines = joinContinuations(lines, params.maxTurnAngle, intensity);
        if (!params.branches && lines.size() > 1) {
            // Only the trace under the click: the other ones can be clicked afterwards
            List<Point> clicked = null;
            double best = Double.MAX_VALUE;
            for (List<Point> line : lines) {
                for (Point p : line) {
                    if (p.distance(seed) < best) {
                        best = p.distance(seed);
                        clicked = line;
                    }
                }
            }
            lines = new ArrayList<>(List.of(clicked));
        }

        // Keep the junctions, which are shared with other lines
        Set<Point> ends = new HashSet<>();
        for (List<Point> line : lines) {
            ends.add(line.get(0));
            ends.add(line.get(line.size() - 1));
        }
        List<List<Point>> result = new ArrayList<>();
        for (List<Point> line : lines) {
            result.add(simplify(line, params.simplifyTolerance, ends));
        }
        return result;
    }

    /**
     * Computes the centre lines of a region, on the bounding box of the region only.
     */
    private static List<List<Point>> centreLines(boolean[][] region, boolean[][] blocked, Params params) {
        int h = region.length;
        int w = region[0].length;
        int x0 = w;
        int y0 = h;
        int x1 = -1;
        int y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (region[y][x]) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        if (x1 < 0) {
            return new ArrayList<>();
        }
        // Margin for the dilation and for the holes near the border
        x0 = Math.max(0, x0 - 2);
        y0 = Math.max(0, y0 - 2);
        x1 = Math.min(w - 1, x1 + 2);
        y1 = Math.min(h - 1, y1 + 2);
        boolean[][] crop = new boolean[y1 - y0 + 1][];
        boolean[][] cropBlocked = blocked == null ? null : new boolean[y1 - y0 + 1][];
        for (int y = y0; y <= y1; y++) {
            crop[y - y0] = Arrays.copyOfRange(region[y], x0, x1 + 1);
            if (blocked != null) {
                cropBlocked[y - y0] = Arrays.copyOfRange(blocked[y], x0, x1 + 1);
            }
        }
        // Close the gaps and smooth the outline, so that the skeleton has fewer spurs
        dilate(crop, cropBlocked);
        int area = fillHoles(crop, params.maxHoleArea);
        boolean[][] skeleton = thin(crop);
        int skeletonLength = 0;
        for (boolean[] row : skeleton) {
            for (boolean b : row) {
                skeletonLength += b ? 1 : 0;
            }
        }
        // Branches shorter than the trace width are artefacts of the thinning
        int width = skeletonLength == 0 ? 0 : area / skeletonLength;
        // Back to window coordinates (the points of the junctions are shared by several lines)
        List<List<Point>> lines = new ArrayList<>();
        for (List<Point> line : polylines(skeleton, Math.max(params.spurLength, 2 * width), cropBlocked)) {
            List<Point> translated = new ArrayList<>(line.size());
            for (Point p : line) {
                translated.add(new Point(p.x + x0, p.y + y0));
            }
            lines.add(translated);
        }
        return lines;
    }

    /** Finds the nearest trace pixel in a cone in front of a free end, or {@code null}. */
    private static Point findContinuation(int[][] intensity, boolean[][] blocked, boolean[][] region, Point end,
            double[] direction, int threshold, int maxJump) {
        if (direction[0] == 0 && direction[1] == 0) {
            return null;
        }
        for (int d = 2; d <= maxJump; d++) {
            for (int a = 0; a <= JUMP_HALF_ANGLE; a += 5) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double angle = Math.toRadians(a * sign);
                    double dx = direction[0] * Math.cos(angle) - direction[1] * Math.sin(angle);
                    double dy = direction[0] * Math.sin(angle) + direction[1] * Math.cos(angle);
                    int x = (int) Math.round(end.x + dx * d);
                    int y = (int) Math.round(end.y + dy * d);
                    if (x >= 0 && y >= 0 && y < intensity.length && x < intensity[0].length && !region[y][x]
                            && intensity[y][x] >= threshold && !isBlocked(blocked, x, y)) {
                        return new Point(x, y);
                    }
                }
            }
        }
        return null;
    }

    /** 3x3 dilation, outside of the blocked pixels. */
    static void dilate(boolean[][] region, boolean[][] blocked) {
        int h = region.length;
        int w = region[0].length;
        boolean[][] source = new boolean[h][];
        for (int y = 0; y < h; y++) {
            source[y] = region[y].clone();
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (source[y][x] || isBlocked(blocked, x, y)) {
                    continue;
                }
                for (int k = 0; k < 8 && !region[y][x]; k++) {
                    int nx = x + DX[k];
                    int ny = y + DY[k];
                    region[y][x] = nx >= 0 && ny >= 0 && nx < w && ny < h && source[ny][nx];
                }
            }
        }
    }

    /**
     * Fills the small holes of a region, which would otherwise become loops in the skeleton.
     * @return area of the region after filling
     */
    static int fillHoles(boolean[][] region, int maxArea) {
        int h = region.length;
        int w = region[0].length;
        boolean[][] seen = new boolean[h][w];
        int area = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (region[y][x]) {
                    area++;
                    continue;
                }
                if (seen[y][x]) {
                    continue;
                }
                // 4-connected component of the background
                List<Point> component = new ArrayList<>();
                int size = 0;
                boolean touchesBorder = false;
                ArrayDeque<Point> queue = new ArrayDeque<>();
                queue.add(new Point(x, y));
                seen[y][x] = true;
                while (!queue.isEmpty()) {
                    Point p = queue.poll();
                    size++;
                    if (size <= maxArea) {
                        component.add(p);
                    }
                    touchesBorder |= p.x == 0 || p.y == 0 || p.x == w - 1 || p.y == h - 1;
                    for (int k = 0; k < 8; k += 2) {
                        int nx = p.x + DX[k];
                        int ny = p.y + DY[k];
                        if (nx >= 0 && ny >= 0 && nx < w && ny < h && !region[ny][nx] && !seen[ny][nx]) {
                            seen[ny][nx] = true;
                            queue.add(new Point(nx, ny));
                        }
                    }
                }
                if (!touchesBorder && size <= maxArea) {
                    for (Point p : component) {
                        region[p.y][p.x] = true;
                    }
                    area += size;
                }
            }
        }
        return area;
    }

    /** Finds the hottest usable pixel around (x, y), or {@code null}. */
    static Point findSeed(int[][] intensity, boolean[][] blocked, int x, int y, int radius, int threshold) {
        Point best = null;
        int bestValue = threshold - 1;
        for (int j = Math.max(0, y - radius); j <= Math.min(intensity.length - 1, y + radius); j++) {
            for (int i = Math.max(0, x - radius); i <= Math.min(intensity[0].length - 1, x + radius); i++) {
                if (intensity[j][i] > bestValue && !isBlocked(blocked, i, j)) {
                    bestValue = intensity[j][i];
                    best = new Point(i, j);
                }
            }
        }
        return best;
    }

    /**
     * Adds to the region the pixels above the threshold connected to the seed,
     * jumping over gaps up to {@code maxGap} pixels.
     */
    static void grow(boolean[][] region, int[][] intensity, boolean[][] blocked, Point seed, int threshold,
            int maxGap) {
        int h = intensity.length;
        int w = intensity[0].length;
        ArrayDeque<Point> queue = new ArrayDeque<>();
        region[seed.y][seed.x] = true;
        queue.add(seed);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (int dy = -maxGap - 1; dy <= maxGap + 1; dy++) {
                for (int dx = -maxGap - 1; dx <= maxGap + 1; dx++) {
                    int nx = p.x + dx;
                    int ny = p.y + dy;
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h && !region[ny][nx]
                            && intensity[ny][nx] >= threshold && !isBlocked(blocked, nx, ny)) {
                        region[ny][nx] = true;
                        queue.add(new Point(nx, ny));
                        // Fill the gap, so that the region stays connected
                        fillGap(region, blocked, intensity, threshold, p, nx, ny);
                    }
                }
            }
        }
    }

    /**
     * Marks the pixels between two points as part of the region. The pixels above the threshold are left
     * to the flood fill, so that it continues from them.
     */
    private static void fillGap(boolean[][] region, boolean[][] blocked, int[][] intensity, int threshold,
            Point from, int toX, int toY) {
        int steps = Math.max(Math.abs(toX - from.x), Math.abs(toY - from.y));
        for (int i = 1; i < steps; i++) {
            int gx = from.x + Math.round((float) (toX - from.x) * i / steps);
            int gy = from.y + Math.round((float) (toY - from.y) * i / steps);
            if (!isBlocked(blocked, gx, gy) && intensity[gy][gx] < threshold) {
                region[gy][gx] = true;
            }
        }
    }

    private static boolean isBlocked(boolean[][] blocked, int x, int y) {
        return blocked != null && blocked[y][x];
    }

    /** Zhang-Suen thinning followed by the removal of the staircase pixels. */
    static boolean[][] thin(boolean[][] source) {
        int h = source.length;
        int w = source[0].length;
        // Copy with a one pixel border so that neighbours are always defined
        boolean[][] img = new boolean[h + 2][w + 2];
        for (int y = 0; y < h; y++) {
            System.arraycopy(source[y], 0, img[y + 1], 1, w);
        }
        boolean changed = true;
        List<Point> toRemove = new ArrayList<>();
        while (changed) {
            changed = false;
            for (int step = 0; step < 2; step++) {
                toRemove.clear();
                for (int y = 1; y <= h; y++) {
                    for (int x = 1; x <= w; x++) {
                        if (img[y][x] && zhangSuenRemovable(img, x, y, step)) {
                            toRemove.add(new Point(x, y));
                        }
                    }
                }
                for (Point p : toRemove) {
                    img[p.y][p.x] = false;
                }
                changed |= !toRemove.isEmpty();
            }
        }
        // Remove the corner pixels of the 4-connected staircases left on diagonal lines,
        // otherwise they look like junctions
        for (int y = 1; y <= h; y++) {
            for (int x = 1; x <= w; x++) {
                if (img[y][x] && isStaircaseCorner(img, x, y)) {
                    img[y][x] = false;
                }
            }
        }
        boolean[][] result = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(img[y + 1], 1, result[y], 0, w);
        }
        return result;
    }

    private static boolean[] neighbourhood(boolean[][] img, int x, int y) {
        boolean[] n = new boolean[8];
        for (int k = 0; k < 8; k++) {
            n[k] = img[y + DY[k]][x + DX[k]];
        }
        return n;
    }

    private static boolean zhangSuenRemovable(boolean[][] img, int x, int y, int step) {
        boolean[] n = neighbourhood(img, x, y);
        int b = 0;
        int a = 0;
        for (int k = 0; k < 8; k++) {
            if (n[k]) {
                b++;
            }
            if (!n[k] && n[(k + 1) % 8]) {
                a++;
            }
        }
        if (b < 2 || b > 6 || a != 1) {
            return false;
        }
        // n[0]=N(P2) n[2]=E(P4) n[4]=S(P6) n[6]=W(P8)
        if (step == 0) {
            return !(n[0] && n[2] && n[4]) && !(n[2] && n[4] && n[6]);
        }
        return !(n[0] && n[2] && n[6]) && !(n[0] && n[4] && n[6]);
    }

    private static boolean isStaircaseCorner(boolean[][] img, int x, int y) {
        boolean[] n = neighbourhood(img, x, y);
        boolean corner = (n[0] && n[2]) || (n[2] && n[4]) || (n[4] && n[6]) || (n[6] && n[0]);
        return corner && countComponents(n) == 1;
    }

    /** Number of 8-connected groups among the neighbours of a pixel. */
    private static int countComponents(boolean[] n) {
        int components = 0;
        boolean[] seen = new boolean[8];
        for (int k = 0; k < 8; k++) {
            if (n[k] && !seen[k]) {
                components++;
                ArrayDeque<Integer> stack = new ArrayDeque<>();
                stack.push(k);
                seen[k] = true;
                while (!stack.isEmpty()) {
                    int c = stack.pop();
                    for (int m = 0; m < 8; m++) {
                        if (n[m] && !seen[m] && adjacent(c, m)) {
                            seen[m] = true;
                            stack.push(m);
                        }
                    }
                }
            }
        }
        return components;
    }

    private static boolean adjacent(int a, int b) {
        return Math.abs(DX[a] - DX[b]) <= 1 && Math.abs(DY[a] - DY[b]) <= 1;
    }

    /** Distance (in pixels) from an existing way at which a free end is considered connected to it. */
    private static final int CONNECTION_DISTANCE = 4;

    /**
     * Splits a one pixel wide skeleton into polylines between end points and junctions.
     * @param skel skeleton
     * @param spurLength minimum length of a branch ending in the void
     * @param blocked pixels of the existing ways, may be {@code null}: a branch ending against them connects
     *        the trace to an existing way, and is kept however short it is
     */
    static List<List<Point>> polylines(boolean[][] skel, int spurLength, boolean[][] blocked) {
        Set<Point> pixels = new HashSet<>();
        for (int y = 0; y < skel.length; y++) {
            for (int x = 0; x < skel[0].length; x++) {
                if (skel[y][x]) {
                    pixels.add(new Point(x, y));
                }
            }
        }
        // Group adjacent junction pixels: each group is considered as a single node
        Map<Point, Point> junction = new HashMap<>();   // junction pixel -> representative pixel
        for (Point p : pixels) {
            if (neighbours(pixels, p).size() > 2 && !junction.containsKey(p)) {
                ArrayDeque<Point> stack = new ArrayDeque<>();
                stack.push(p);
                junction.put(p, p);
                while (!stack.isEmpty()) {
                    for (Point n : neighbours(pixels, stack.pop())) {
                        if (!junction.containsKey(n) && neighbours(pixels, n).size() > 2) {
                            junction.put(n, p);
                            stack.push(n);
                        }
                    }
                }
            }
        }

        Set<Set<Point>> visited = new HashSet<>();
        List<List<Point>> lines = new ArrayList<>();
        for (Point p : pixels) {
            if (neighbours(pixels, p).size() != 2) {
                for (Point n : neighbours(pixels, p)) {
                    if (!visited.contains(Set.of(p, n))) {
                        lines.add(walk(pixels, junction, visited, p, n));
                    }
                }
            }
        }
        // Remaining pixels belong to loops without any end point or junction
        for (Point p : pixels) {
            for (Point n : neighbours(pixels, p)) {
                if (!visited.contains(Set.of(p, n))) {
                    lines.add(walk(pixels, junction, visited, p, n));
                }
            }
        }

        // Replace the end pixels in a junction by the representative of the junction
        List<List<Point>> collapsed = new ArrayList<>();
        for (List<Point> line : lines) {
            List<Point> c = collapse(line, junction);
            if (c.size() >= 2 && !c.get(0).equals(c.get(c.size() - 1)) || c.size() > 3) {
                collapsed.add(c);
            }
        }

        // Remove the short spurs and merge the remaining pieces, until nothing changes:
        // removing a spur may create a new short dangling piece
        List<List<Point>> result = merge(collapsed);
        boolean removed = true;
        while (removed && result.size() > 1) {
            Map<Point, Integer> degree = endpointDegrees(result);
            // Noise attached to the trace gives branches that are short compared to the trace
            double longest = result.stream().mapToDouble(TraceExtractor::length).max().getAsDouble();
            double minLength = Math.max(spurLength, MIN_BRANCH_RATIO * longest);
            List<List<Point>> kept = new ArrayList<>();
            for (List<Point> line : result) {
                Point start = line.get(0);
                Point end = line.get(line.size() - 1);
                boolean dangling = degree.get(start) == 1 && !nearBlocked(blocked, start)
                        || degree.get(end) == 1 && !nearBlocked(blocked, end);
                if (!dangling || length(line) >= minLength) {
                    kept.add(line);
                }
            }
            removed = kept.size() < result.size();
            if (kept.isEmpty()) {
                // Only short pieces: keep the longest one
                kept.add(result.stream().max((a, b) -> Double.compare(length(a), length(b))).get());
            }
            result = merge(kept);
        }
        return result;
    }

    /** Tells if a pixel is close to an existing way. */
    private static boolean nearBlocked(boolean[][] blocked, Point p) {
        if (blocked == null) {
            return false;
        }
        for (int dy = -CONNECTION_DISTANCE; dy <= CONNECTION_DISTANCE; dy++) {
            for (int dx = -CONNECTION_DISTANCE; dx <= CONNECTION_DISTANCE; dx++) {
                int x = p.x + dx;
                int y = p.y + dy;
                if (y >= 0 && y < blocked.length && x >= 0 && x < blocked[0].length && blocked[y][x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double length(List<Point> line) {
        double length = 0;
        for (int i = 1; i < line.size(); i++) {
            length += line.get(i - 1).distance(line.get(i));
        }
        return length;
    }

    /** Maximum turn (in degrees) between the two dominant branches of a junction joined as a single way. */
    private static final double MAX_DOMINANT_TURN_ANGLE = 90;
    /** The other branches of a junction must be at most this part as hot as the two dominant ones. */
    private static final double DOMINANCE_RATIO = 0.5;
    /** Number of pixels of a branch, from the junction, over which its heat is measured. */
    private static final int HEAT_PIXELS = 20;

    /**
     * At each junction, joins the pairs of branches that continue each other, so that a trace crossed
     * or touched by another one remains a single line: the two branches much hotter than the others
     * (a popular trace bending at a faint fork), or else the two straightest ones.
     * @param input polylines (pixel chains)
     * @param maxTurnAngle maximum deviation (in degrees) between two branches continuing each other
     * @param intensity heatmap intensities, may be {@code null} to only look at the angles
     */
    static List<List<Point>> joinContinuations(List<List<Point>> input, double maxTurnAngle, int[][] intensity) {
        List<List<Point>> lines = new ArrayList<>();
        for (List<Point> line : input) {
            lines.add(new ArrayList<>(line));
        }
        double minCos = -Math.cos(Math.toRadians(maxTurnAngle));
        boolean joined = true;
        while (joined) {
            joined = false;
            Map<Point, List<int[]>> incidences = new HashMap<>();    // node -> {line index, 0=start/1=end}
            for (int i = 0; i < lines.size(); i++) {
                List<Point> line = lines.get(i);
                if (line.get(0).equals(line.get(line.size() - 1))) {
                    continue;   // Loops are left alone
                }
                incidences.computeIfAbsent(line.get(0), k -> new ArrayList<>()).add(new int[] {i, 0});
                incidences.computeIfAbsent(line.get(line.size() - 1), k -> new ArrayList<>()).add(new int[] {i, 1});
            }
            double bestCos = minCos;
            int[] bestA = null;
            int[] bestB = null;
            for (List<int[]> inc : incidences.values()) {
                if (inc.size() < 3) {
                    continue;
                }
                int[][] dominant = dominantPair(lines, inc, intensity);
                if (dominant != null) {
                    bestA = dominant[0];
                    bestB = dominant[1];
                    break;
                }
                for (int a = 0; a < inc.size(); a++) {
                    for (int b = a + 1; b < inc.size(); b++) {
                        if (inc.get(a)[0] == inc.get(b)[0]) {
                            continue;
                        }
                        double[] da = direction(lines.get(inc.get(a)[0]), inc.get(a)[1] == 0);
                        double[] db = direction(lines.get(inc.get(b)[0]), inc.get(b)[1] == 0);
                        // Opposite directions from the junction: the branches continue each other
                        double cos = da[0] * db[0] + da[1] * db[1];
                        if (cos < bestCos) {
                            bestCos = cos;
                            bestA = inc.get(a);
                            bestB = inc.get(b);
                        }
                    }
                }
            }
            if (bestA != null) {
                List<Point> a = lines.get(bestA[0]);
                List<Point> b = lines.get(bestB[0]);
                List<Point> first = bestA[1] == 1 ? a : reversed(a);    // Ends at the junction
                List<Point> second = bestB[1] == 0 ? b : reversed(b);   // Starts at the junction
                lines.set(bestA[0], concat(first, second));
                lines.remove(bestB[0]);
                joined = true;
            }
        }
        return lines;
    }

    /**
     * Returns the two branches of a junction that are much hotter than the other ones, if they continue
     * each other with a turn below {@link #MAX_DOMINANT_TURN_ANGLE}, or {@code null}.
     */
    private static int[][] dominantPair(List<List<Point>> lines, List<int[]> inc, int[][] intensity) {
        if (intensity == null) {
            return null;
        }
        Integer[] order = new Integer[inc.size()];
        double[] heat = new double[inc.size()];
        for (int k = 0; k < inc.size(); k++) {
            order[k] = k;
            heat[k] = heat(lines.get(inc.get(k)[0]), inc.get(k)[1] == 0, intensity);
        }
        Arrays.sort(order, (a, b) -> Double.compare(heat[b], heat[a]));
        int[] a = inc.get(order[0]);
        int[] b = inc.get(order[1]);
        if (a[0] == b[0] || heat[order[2]] > DOMINANCE_RATIO * heat[order[1]]) {
            return null;
        }
        double[] da = direction(lines.get(a[0]), a[1] == 0);
        double[] db = direction(lines.get(b[0]), b[1] == 0);
        if (da[0] * db[0] + da[1] * db[1] >= -Math.cos(Math.toRadians(MAX_DOMINANT_TURN_ANGLE))) {
            return null;
        }
        return new int[][] {a, b};
    }

    /** Mean intensity of the first pixels of a line, from one of its ends. */
    private static double heat(List<Point> line, boolean fromStart, int[][] intensity) {
        int n = Math.min(HEAT_PIXELS, line.size());
        double sum = 0;
        for (int i = 0; i < n; i++) {
            Point p = line.get(fromStart ? i : line.size() - 1 - i);
            sum += intensity[p.y][p.x];
        }
        return sum / n;
    }

    /** Unit vector from an end of a line towards a point about 8 pixels along it. */
    private static double[] direction(List<Point> line, boolean fromStart) {
        List<Point> l = fromStart ? line : reversed(line);
        Point origin = l.get(0);
        Point target = l.get(l.size() - 1);
        for (Point p : l) {
            if (p.distance(origin) >= 8) {
                target = p;
                break;
            }
        }
        double dx = target.x - origin.x;
        double dy = target.y - origin.y;
        double norm = Math.hypot(dx, dy);
        return norm == 0 ? new double[] {0, 0} : new double[] {dx / norm, dy / norm};
    }

    private static List<Point> neighbours(Set<Point> pixels, Point p) {
        List<Point> result = new ArrayList<>(8);
        for (int k = 0; k < 8; k++) {
            Point n = new Point(p.x + DX[k], p.y + DY[k]);
            if (pixels.contains(n)) {
                result.add(n);
            }
        }
        return result;
    }

    private static List<Point> walk(Set<Point> pixels, Map<Point, Point> junction, Set<Set<Point>> visited,
            Point start, Point next) {
        List<Point> line = new ArrayList<>();
        line.add(start);
        line.add(next);
        visited.add(Set.of(start, next));
        Point prev = start;
        Point cur = next;
        while (neighbours(pixels, cur).size() == 2 && !cur.equals(start)) {
            Point candidate = null;
            for (Point n : neighbours(pixels, cur)) {
                if (!n.equals(prev) && !visited.contains(Set.of(cur, n))) {
                    candidate = n;
                    break;
                }
            }
            if (candidate == null) {
                break;
            }
            visited.add(Set.of(cur, candidate));
            prev = cur;
            cur = candidate;
            line.add(cur);
        }
        return line;
    }

    private static List<Point> collapse(List<Point> line, Map<Point, Point> junction) {
        int first = 0;
        int last = line.size() - 1;
        Point startRep = junction.get(line.get(0));
        Point endRep = junction.get(line.get(last));
        while (startRep != null && first < last && startRep.equals(junction.get(line.get(first + 1)))) {
            first++;
        }
        while (endRep != null && last > first && endRep.equals(junction.get(line.get(last - 1)))) {
            last--;
        }
        List<Point> result = new ArrayList<>();
        result.add(startRep != null ? startRep : line.get(first));
        if (first < last) {
            result.addAll(line.subList(first + 1, last));
        }
        result.add(endRep != null ? endRep : line.get(last));
        return result;
    }

    private static Map<Point, Integer> endpointDegrees(List<List<Point>> lines) {
        Map<Point, Integer> degree = new HashMap<>();
        for (List<Point> line : lines) {
            degree.merge(line.get(0), 1, Integer::sum);
            degree.merge(line.get(line.size() - 1), 1, Integer::sum);
        }
        return degree;
    }

    /** Joins the polylines meeting at a point shared by exactly two of them. */
    static List<List<Point>> merge(List<List<Point>> input) {
        List<List<Point>> lines = new ArrayList<>();
        for (List<Point> line : input) {
            lines.add(new ArrayList<>(line));
        }
        boolean merged = true;
        while (merged) {
            merged = false;
            Map<Point, Integer> degree = endpointDegrees(lines);
            outer:
            for (int i = 0; i < lines.size(); i++) {
                for (int j = i + 1; j < lines.size(); j++) {
                    List<Point> a = lines.get(i);
                    List<Point> b = lines.get(j);
                    Point aStart = a.get(0);
                    Point aEnd = a.get(a.size() - 1);
                    Point bStart = b.get(0);
                    Point bEnd = b.get(b.size() - 1);
                    List<Point> joined = null;
                    if (aEnd.equals(bStart) && degree.get(aEnd) == 2) {
                        joined = concat(a, b);
                    } else if (aEnd.equals(bEnd) && degree.get(aEnd) == 2) {
                        joined = concat(a, reversed(b));
                    } else if (aStart.equals(bEnd) && degree.get(aStart) == 2) {
                        joined = concat(b, a);
                    } else if (aStart.equals(bStart) && degree.get(aStart) == 2) {
                        joined = concat(reversed(b), a);
                    }
                    if (joined != null) {
                        lines.set(i, joined);
                        lines.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        return lines;
    }

    private static List<Point> concat(List<Point> a, List<Point> b) {
        List<Point> result = new ArrayList<>(a);
        result.addAll(b.subList(1, b.size()));
        return result;
    }

    private static List<Point> reversed(List<Point> line) {
        List<Point> result = new ArrayList<>(line);
        java.util.Collections.reverse(result);
        return result;
    }

    /** Douglas-Peucker simplification, keeping the given points. */
    static List<Point> simplify(List<Point> line, double tolerance, Set<Point> fixed) {
        if (line.size() <= 2) {
            return new ArrayList<>(line);
        }
        boolean[] keep = new boolean[line.size()];
        keep[0] = true;
        keep[line.size() - 1] = true;
        List<Integer> anchors = new ArrayList<>();
        anchors.add(0);
        for (int i = 1; i < line.size() - 1; i++) {
            if (fixed.contains(line.get(i))) {
                keep[i] = true;
                anchors.add(i);
            }
        }
        anchors.add(line.size() - 1);
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        for (int i = 1; i < anchors.size(); i++) {
            stack.push(new int[] {anchors.get(i - 1), anchors.get(i)});
        }
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            double maxDistance = -1;
            int index = -1;
            for (int i = range[0] + 1; i < range[1]; i++) {
                double d = distanceToSegment(line.get(i), line.get(range[0]), line.get(range[1]));
                if (d > maxDistance) {
                    maxDistance = d;
                    index = i;
                }
            }
            if (index >= 0 && maxDistance > tolerance) {
                keep[index] = true;
                stack.push(new int[] {range[0], index});
                stack.push(new int[] {index, range[1]});
            }
        }
        List<Point> result = new ArrayList<>();
        for (int i = 0; i < line.size(); i++) {
            if (keep[i]) {
                result.add(line.get(i));
            }
        }
        return result;
    }

    private static double distanceToSegment(Point p, Point a, Point b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        if (len2 == 0) {
            return p.distance(a);
        }
        double t = Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2));
        return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy));
    }
}
