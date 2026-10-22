// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class HeatmapWindowTest {

    private static final int[] LEVELS = {0, 1, 40, 128, 200, 255};

    /** An indexed tile like Strava's: palette colours of the given style, alpha ramp from 0 to 255. */
    private static BufferedImage indexed(int[] rgb) throws Exception {
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        byte[] a = new byte[256];
        for (int i = 0; i < 256; i++) {
            r[i] = (byte) (rgb[i] >> 16);
            g[i] = (byte) (rgb[i] >> 8);
            b[i] = (byte) rgb[i];
            // Like Strava: transparent at 0, then quickly translucent, opaque at 255
            a[i] = (byte) (i == 0 ? 0 : Math.min(255, 80 + i * 175 / 255));
        }
        BufferedImage image = new BufferedImage(LEVELS.length, 1, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 256, r, g, b, a));
        for (int x = 0; x < LEVELS.length; x++) {
            image.getRaster().setSample(x, 0, 0, LEVELS[x]);
        }
        return roundTrip(image);
    }

    private static BufferedImage roundTrip(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    void bluePaletteGivesTheHeatLevel() throws Exception {
        // Strava's "blue" palette goes from dark violet to light blue and white
        int[] blue = new int[256];
        for (int i = 0; i < 256; i++) {
            blue[i] = (16 + i * 239 / 255) << 16 | (i * 255 / 255) << 8 | (75 + i * 180 / 255);
        }
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(indexed(blue), false));
        // Even when the URL says "hot"
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(indexed(blue), true));
    }

    @Test
    void hotPaletteGivesTheHeatLevel() throws Exception {
        int[] hot = new int[256];
        for (int i = 0; i < 256; i++) {
            hot[i] = StravaPalette.hotColour(i);
        }
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(indexed(hot), true));
    }

    @Test
    void greyTileGivesTheHeatLevel() throws Exception {
        BufferedImage grey = new BufferedImage(LEVELS.length, 1, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < LEVELS.length; x++) {
            grey.getRaster().setSample(x, 0, 0, LEVELS[x]);
        }
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(roundTrip(grey), true));
    }

    @Test
    void paintedHotTileIsReadFromItsColours() throws Exception {
        // An RGBA image (not one of Strava's encodings) still works for the "hot" colours
        BufferedImage painted = new BufferedImage(LEVELS.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < LEVELS.length; x++) {
            painted.setRGB(x, 0, LEVELS[x] == 0 ? 0 : 0xFF000000 | StravaPalette.hotColour(LEVELS[x]));
        }
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(roundTrip(painted), true));
    }
}
