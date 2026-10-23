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

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path josmHome;

    @org.junit.jupiter.api.BeforeAll
    static void setUpJosm() {
        // A temporary JOSM home and tile cache, never the ones of the user
        System.setProperty("josm.home", josmHome.toString());
        System.setProperty("josm.cache", josmHome.resolve("cache").toString());
        org.openstreetmap.josm.data.preferences.JosmBaseDirectories dirs =
                org.openstreetmap.josm.data.preferences.JosmBaseDirectories.getInstance();
        org.openstreetmap.josm.spi.preferences.Config.setBaseDirectoriesProvider(dirs);
        org.openstreetmap.josm.spi.preferences.Config.setPreferencesInstance(
                new org.openstreetmap.josm.data.Preferences(dirs));
        org.openstreetmap.josm.spi.preferences.Config.setUrlsProvider(
                org.openstreetmap.josm.data.preferences.JosmUrls.getInstance());
    }

    /** The tiles already shown by JOSM are read from its tile cache, with the same key as its tile loader. */
    @Test
    void tilesAreReadFromTheJosmCache() throws Exception {
        org.openstreetmap.josm.data.imagery.ImageryInfo info = new org.openstreetmap.josm.data.imagery.ImageryInfo(
                "strava test:1", "https://content-a.strava.com/identified/globalheat/all/blue/{zoom}/{x}/{y}.png",
                "tms", null, null);
        org.openstreetmap.gui.jmapviewer.tilesources.AbstractTMSTileSource source =
                org.openstreetmap.josm.gui.layer.TMSLayer.getTileSourceStatic(info);
        org.apache.commons.jcs3.access.CacheAccess<String, org.openstreetmap.josm.data.cache.BufferedImageCacheEntry>
                cache = org.openstreetmap.josm.gui.layer.TMSLayer.getCache();
        String key = new org.openstreetmap.josm.data.imagery.TMSCachedTileLoaderJob(null,
                new org.openstreetmap.gui.jmapviewer.Tile(source, 12704, 17698, 15), cache,
                new org.openstreetmap.josm.data.imagery.TileJobOptions(1000, 1000, java.util.Map.of(), 0), null)
                .getCacheKey();
        // A tile shown on the map: the original PNG bytes, with the palette
        int[] blue = new int[256];
        for (int i = 0; i < 256; i++) {
            blue[i] = (16 + i * 239 / 255) << 16 | i << 8 | (75 + i * 180 / 255);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(indexed(blue), "png", png);
        org.openstreetmap.josm.data.cache.CacheEntryAttributes ok = new org.openstreetmap.josm.data.cache.CacheEntryAttributes();
        ok.setResponseCode(200);
        cache.put(key, new org.openstreetmap.josm.data.cache.BufferedImageCacheEntry(png.toByteArray()), ok);
        java.util.List<String> log = new java.util.ArrayList<>();
        BufferedImage tile = HeatmapWindow.tile(info, source, 15, 12704, 17698, log);
        assertArrayEquals(LEVELS, HeatmapWindow.intensities(tile, false));
        org.junit.jupiter.api.Assertions.assertTrue(log.get(0).contains("JOSM cache"), log.toString());
        // A tile without activity (HTTP 404 kept in the cache): no download either
        String emptyKey = new org.openstreetmap.josm.data.imagery.TMSCachedTileLoaderJob(null,
                new org.openstreetmap.gui.jmapviewer.Tile(source, 12705, 17698, 15), cache,
                new org.openstreetmap.josm.data.imagery.TileJobOptions(1000, 1000, java.util.Map.of(), 0), null)
                .getCacheKey();
        org.openstreetmap.josm.data.cache.CacheEntryAttributes missing = new org.openstreetmap.josm.data.cache.CacheEntryAttributes();
        missing.setResponseCode(404);
        cache.put(emptyKey, new org.openstreetmap.josm.data.cache.BufferedImageCacheEntry(new byte[0]), missing);
        log.clear();
        org.junit.jupiter.api.Assertions.assertNull(HeatmapWindow.tile(info, source, 15, 12705, 17698, log));
        org.junit.jupiter.api.Assertions.assertTrue(log.get(0).contains("JOSM cache"), log.toString());
    }

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
