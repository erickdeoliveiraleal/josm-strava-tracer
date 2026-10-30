// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class TraceExtractorTest {

    private static final int SIZE = 512;

    /** Renders polylines with the given intensity and width, blurred, then coloured with the "hot" palette. */
    private static int[][] heatmap(int intensity, float width, int[]... polylines) {
        BufferedImage grey = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(intensity, intensity, intensity));
        g.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int[] line : polylines) {
            for (int i = 0; i + 3 < line.length; i += 2) {
                g.drawLine(line[i], line[i + 1], line[i + 2], line[i + 3]);
            }
        }
        g.dispose();
        float[] k = new float[9];
        java.util.Arrays.fill(k, 1f / 9);
        BufferedImage blurred = new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(grey, null);

        int[][] result = new int[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int v = blurred.getRaster().getSample(x, y, 0);
                // Empty pixels are transparent, like on the real tiles
                int argb = v == 0 ? 0 : 0xFF000000 | StravaPalette.hotColour(v);
                result[y][x] = StravaPalette.intensity(argb, true);
            }
        }
        return result;
    }

    @Test
    void paletteRoundTrip() {
        for (int i = 0; i < 256; i++) {
            assertEquals(i, StravaPalette.intensity(0xFF000000 | StravaPalette.hotColour(i), true));
        }
        assertEquals(0, StravaPalette.intensity(0x00FFFFFF, true));
        // Real Strava pixels: the alpha grows with the intensity and must not lower it
        assertEquals(StravaPalette.intensity(0xFF51002C, true), StravaPalette.intensity(0x9C51002C, true));
    }

    @Test
    void yShapedTrailGivesMainLineAndBranch() {
        int[][] map = heatmap(230, 6,
                new int[] {60, 300, 150, 280, 250, 330, 330, 340, 450, 300},
                new int[] {250, 330, 270, 420, 300, 480});
        TraceExtractor.Params params = new TraceExtractor.Params();
        params.branches = true;
        List<List<Point>> lines = TraceExtractor.extract(map, null, 150, 282, params);

        // The left and right parts continue each other: one line, plus the branch
        assertEquals(2, lines.size(), lines.toString());
        List<Point> main = lines.get(0).size() >= lines.get(1).size() ? lines.get(0) : lines.get(1);
        List<Point> branch = main == lines.get(0) ? lines.get(1) : lines.get(0);
        assertTrue(main.contains(branch.get(0)) || main.contains(branch.get(branch.size() - 1)),
                "the branch must start on the main line: " + lines);
        // Free ends are close to the drawn ends
        assertTrue(hasEndNear(lines, 60, 300, 6), lines.toString());
        assertTrue(hasEndNear(lines, 450, 300, 6), lines.toString());
        assertTrue(hasEndNear(lines, 300, 480, 6), lines.toString());
    }

    @Test
    void onlyTheClickedTraceIsReturnedByDefault() {
        int[][] map = heatmap(230, 6,
                new int[] {60, 300, 150, 280, 250, 330, 330, 340, 450, 300},
                new int[] {250, 330, 270, 420, 300, 480});
        // Click on the branch: only the branch, up to the junction
        List<List<Point>> lines = TraceExtractor.extract(map, null, 285, 450, new TraceExtractor.Params());
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(hasEndNear(lines, 300, 480, 6), lines.toString());
        assertTrue(hasEndNear(lines, 250, 330, 8), lines.toString());
        // Click on the main trace: the main trace, without the branch
        lines = TraceExtractor.extract(map, null, 150, 282, new TraceExtractor.Params());
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(hasEndNear(lines, 60, 300, 6), lines.toString());
        assertTrue(hasEndNear(lines, 450, 300, 6), lines.toString());
    }

    @Test
    void diagonalLineIsSimplifiedToOneSegment() {
        int[][] map = heatmap(200, 5, new int[] {50, 60, 400, 380});
        List<List<Point>> lines = TraceExtractor.extract(map, null, 200, 197, new TraceExtractor.Params());
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(2, lines.get(0).size(), lines.toString());
    }

    @Test
    void blockedPixelsAreIgnored() {
        int[][] map = heatmap(230, 6, new int[] {50, 250, 450, 250});
        boolean[][] blocked = new boolean[SIZE][SIZE];
        for (int y = 230; y < 270; y++) {
            for (int x = 0; x < SIZE; x++) {
                blocked[y][x] = true;      // An existing way along the whole trace
            }
        }
        assertTrue(TraceExtractor.extract(map, blocked, 250, 250, new TraceExtractor.Params()).isEmpty());

        // Only the left part is mapped: the right part is traced, starting at the edge of the mask
        for (int y = 230; y < 270; y++) {
            for (int x = 250; x < SIZE; x++) {
                blocked[y][x] = false;
            }
        }
        List<List<Point>> lines = TraceExtractor.extract(map, blocked, 350, 250, new TraceExtractor.Params());
        assertEquals(1, lines.size(), lines.toString());
        int minX = lines.get(0).stream().mapToInt(p -> p.x).min().getAsInt();
        assertTrue(minX >= 250 && minX < 256, lines.toString());
    }

    @Test
    void faintNoisyTrailGivesOneLine() {
        // Unpopular trail: low intensity, variable width, GPS noise around it and holes in it
        int[][] map = heatmap(35, 7, new int[] {40, 400, 120, 330, 200, 300, 300, 250, 380, 160, 470, 120});
        Random random = new Random(42);
        for (int k = 0; k < 4000; k++) {
            int x = 30 + random.nextInt(450);
            int y = 100 + random.nextInt(320);
            map[y][x] = Math.max(map[y][x], 5 + random.nextInt(25));
        }
        for (int k = 0; k < 60; k++) {
            int x = 40 + random.nextInt(430);
            int y = 120 + random.nextInt(280);
            map[y][x] = 0;
        }
        TraceExtractor.Params params = new TraceExtractor.Params();
        List<List<Point>> lines = TraceExtractor.extract(map, null, 200, 300, params);
        assertEquals(1, lines.size(), "threshold " + params.effectiveThreshold + ": " + lines);
        // The end may follow the noise a little beyond the trace
        assertTrue(hasEndNear(lines, 40, 400, 15), lines.toString());
        assertTrue(hasEndNear(lines, 470, 120, 15), lines.toString());
    }

    @Test
    void popularTrailIgnoresTheFaintActivityAround() {
        int[][] map = heatmap(200, 6, new int[] {50, 250, 450, 250});
        // Faint activity spreading from the popular trail
        int[][] faint = heatmap(30, 40, new int[] {200, 250, 230, 380});
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                map[y][x] = Math.max(map[y][x], faint[y][x]);
            }
        }
        List<List<Point>> lines = TraceExtractor.extract(map, null, 300, 250, new TraceExtractor.Params());
        assertEquals(1, lines.size(), lines.toString());
        assertEquals(2, lines.get(0).size(), lines.toString());
    }

    private static boolean hasEndNear(List<List<Point>> lines, int x, int y, double tolerance) {
        for (List<Point> line : lines) {
            for (Point end : List.of(line.get(0), line.get(line.size() - 1))) {
                if (end.distance(x, y) <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A popular trace bends at a faint fork, then reaches a mapped road after a short stretch: the trace
     * follows the popular trail to the road, instead of turning into the faint branch (seen at
     * -15.4535, -49.4368).
     */
    @Test
    void popularTraceIsFollowedAtAFaintForkUpToTheRoad() {
        // A long popular trail, then a short stretch (less than a tenth of it) turning by about 60 degrees
        int[][] strong = heatmap(230, 7, new int[] {330, 0, 260, 400, 235, 410, 226, 418});
        int[][] faint = heatmap(70, 3, new int[] {260, 400, 300, 405, 420, 330});
        int[][] intensity = new int[SIZE][SIZE];
        boolean[][] road = new boolean[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                intensity[y][x] = Math.max(strong[y][x], faint[y][x]);
                road[y][x] = y >= 421;     // The mapped road, masked
            }
        }
        List<List<Point>> lines = TraceExtractor.extract(intensity, road, 300, 170, new TraceExtractor.Params());
        assertEquals(1, lines.size());
        List<Point> line = lines.get(0);
        Point top = line.get(0).y < line.get(line.size() - 1).y ? line.get(0) : line.get(line.size() - 1);
        Point bottom = top == line.get(0) ? line.get(line.size() - 1) : line.get(0);
        assertTrue(top.y < 20, "top end " + top);
        // Down to the road, not into the faint branch
        assertTrue(bottom.y > 405 && bottom.x < 245, "bottom end " + bottom);
        for (Point p : line) {
            assertTrue(p.y < 395 || p.x < 270, "the trace turns into the faint branch at " + p);
        }
    }
}
