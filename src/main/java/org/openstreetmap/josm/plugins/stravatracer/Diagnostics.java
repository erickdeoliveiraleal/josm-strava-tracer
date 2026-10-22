// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Writes the heatmap window, the mask and some statistics to the JOSM user directory,
 * to understand why a trace was not found.
 */
final class Diagnostics {

    private static final int AROUND = 30;

    private Diagnostics() {
    }

    /**
     * Writes the diagnostic files.
     * @return the directory containing the files
     */
    static File dump(HeatmapWindow window, boolean[][] mask, int clickX, int clickY, int threshold)
            throws IOException {
        File dir = new File(Config.getDirs().getUserDataDirectory(true), "stravatracer-debug");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        int w = window.getWidth();
        int h = window.getHeight();
        int[][] intensity = window.getIntensity();

        BufferedImage raw = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage grey = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (window.getArgb() != null) {
                    raw.setRGB(x, y, window.getArgb()[y][x]);
                }
                int v = intensity[y][x];
                // Masked pixels in blue, pixels above the threshold in red
                int rgb = mask[y][x] ? 0x0000A0 | (v << 16) : v >= threshold ? 0xFF0000 | (v << 8) : v * 0x010101;
                grey.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(raw, "png", new File(dir, "tiles.png"));
        ImageIO.write(grey, "png", new File(dir, "intensity.png"));

        try (PrintWriter out = new PrintWriter(new File(dir, "info.txt"), StandardCharsets.UTF_8)) {
            out.println("zoom " + window.getZoom() + ", tile size " + window.getTileSize()
                    + ", window " + w + "x" + h + ", click pixel " + clickX + "," + clickY
                    + ", threshold " + threshold);
            window.getLog().forEach(out::println);

            int max = 0;
            int maxUnmasked = 0;
            int masked = 0;
            Map<Integer, Integer> colours = new HashMap<>();
            for (int y = Math.max(0, clickY - AROUND); y < Math.min(h, clickY + AROUND); y++) {
                for (int x = Math.max(0, clickX - AROUND); x < Math.min(w, clickX + AROUND); x++) {
                    max = Math.max(max, intensity[y][x]);
                    if (mask[y][x]) {
                        masked++;
                    } else {
                        maxUnmasked = Math.max(maxUnmasked, intensity[y][x]);
                    }
                    if (window.getArgb() != null) {
                        colours.merge(window.getArgb()[y][x], 1, Integer::sum);
                    }
                }
            }
            out.println("Within " + AROUND + " px of the click: max intensity " + max
                    + ", max unmasked " + maxUnmasked + ", masked pixels " + masked);
            out.println("Most frequent colours (ARGB -> intensity):");
            colours.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue(Comparator.reverseOrder()))
                    .limit(40)
                    .forEach(e -> out.printf("  %08X x%d -> %d%n", e.getKey(), e.getValue(),
                            StravaPalette.intensity(e.getKey(), true)));
        }
        return dir;
    }
}
