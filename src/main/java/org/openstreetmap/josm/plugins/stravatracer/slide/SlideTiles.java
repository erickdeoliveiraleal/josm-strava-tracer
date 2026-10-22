// License: MIT. Port of strava-heatmap-extension by Julien ETIENNE (julcnx). For details, see the NOTICE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import org.openstreetmap.gui.jmapviewer.tilesources.AbstractTMSTileSource;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.layer.TMSLayer;
import org.openstreetmap.josm.plugins.stravatracer.HeatmapWindow;

/**
 * Downloads and decodes the heatmap tiles that Slide needs (port of {@code slide/tiles.js} and of the
 * density decoding of {@code slide/png.js}).
 */
public final class SlideTiles {

    public static final int SLIDE_ZOOM = 15;

    /** The downloaded tiles and the padded pixel area around the line. */
    public static final class Loaded {
        public final List<SlideAlgorithm.Tile> tiles;
        public final SlideAlgorithm.Bounds bounds;

        Loaded(List<SlideAlgorithm.Tile> tiles, SlideAlgorithm.Bounds bounds) {
            this.tiles = tiles;
            this.bounds = bounds;
        }
    }

    private SlideTiles() {
    }

    /**
     * Downloads every tile around the line.
     * @param info imagery of the Strava layer
     * @param locations [lon, lat] vertices of the line
     * @param cancelled cancellation flag
     * @param onProgress progress (0..1)
     * @return the tiles and the padded bounds
     * @throws IOException if a tile cannot be downloaded or decoded
     */
    public static Loaded load(ImageryInfo info, List<double[]> locations, BooleanSupplier cancelled,
            DoubleConsumer onProgress) throws IOException {
        int zoom = SLIDE_ZOOM;
        // Pad the line's bounding box by the widest search radius plus the contrast neighbourhood.
        double padding = Math.ceil((SlideAlgorithm.MAX_SEARCH_RADIUS_METERS + SlideAlgorithm.CONTRAST_RADIUS_METERS)
                / SlideAlgorithm.metersPerPixel(locations.get(0)[1], zoom)) + 3;
        SlideAlgorithm.Bounds bounds = SlideAlgorithm.lineBounds(locations, zoom, padding);
        if ((double) (bounds.maxX - bounds.minX) * (bounds.maxY - bounds.minY) > 4000000) {
            throw new IOException(tr("Slide area is too large. Select a shorter section of the line."));
        }
        int minX = Math.floorDiv(bounds.minX, 512);
        int maxX = Math.floorDiv(bounds.maxX, 512);
        int minY = Math.floorDiv(bounds.minY, 512);
        int maxY = Math.floorDiv(bounds.maxY, 512);
        if ((maxX - minX + 1) * (maxY - minY + 1) > 16) {
            throw new IOException(tr("Slide needs too many tiles. Select a shorter section of the line."));
        }
        AbstractTMSTileSource source = TMSLayer.getTileSourceStatic(info);
        Map<String, SlideAlgorithm.Tile> cache = new HashMap<>();
        LinkedHashSet<SlideAlgorithm.Tile> tiles = new LinkedHashSet<>();
        int total = (maxX - minX + 1) * (maxY - minY + 1);
        int done = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (cancelled.getAsBoolean()) {
                    throw new SlideAlgorithm.CancelledException();
                }
                SlideAlgorithm.Tile tile = load(source, info, zoom, x, y, cache);
                if (tile == null) {
                    // No tile at this zoom (Strava has no data there): fall back to the parent tile
                    tile = load(source, info, zoom - 1, Math.floorDiv(x, 2), Math.floorDiv(y, 2), cache);
                }
                // A missing child AND parent is an actual coverage hole: the algorithm never traces through it
                if (tile != null) {
                    tiles.add(tile);
                }
                if (onProgress != null) {
                    onProgress.accept((double) ++done / total);
                }
            }
        }
        if (tiles.isEmpty()) {
            throw new IOException(tr("No heatmap tiles are available in this area."));
        }
        return new Loaded(new ArrayList<>(tiles), bounds);
    }

    private static SlideAlgorithm.Tile load(AbstractTMSTileSource source, ImageryInfo info, int z, int x, int y,
            Map<String, SlideAlgorithm.Tile> cache) throws IOException {
        String key = z + "/" + x + "/" + y;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        BufferedImage image = HeatmapWindow.cachedDownload(info, source.getTileUrl(z, x, y), new ArrayList<>());
        SlideAlgorithm.Tile tile = image == null ? null : new SlideAlgorithm.Tile(z, x, y, image.getWidth(),
                image.getHeight(), decode(image));
        cache.put(key, tile);
        return tile;
    }

    /**
     * Tells if a tile stores the heat level itself, whatever the palette it is shown with: an indexed tile
     * with Strava's ordered 256-level alpha ramp, or an 8-bit grey level tile.
     * @param image tile image
     * @return {@code true} for Strava's heat encodings
     */
    public static boolean isHeatEncoded(BufferedImage image) {
        ColorModel cm = image.getColorModel();
        if (cm instanceof IndexColorModel) {
            return isOrderedRamp((IndexColorModel) cm);
        }
        return cm.getNumColorComponents() == 1 && image.getRaster().getSampleModel().getSampleSize(0) == 8;
    }

    private static boolean isOrderedRamp(IndexColorModel icm) {
        if (icm.getMapSize() != 256 || icm.getAlpha(0) != 0 || icm.getAlpha(255) != 255) {
            return false;
        }
        for (int i = 1; i < 256; i++) {
            if (icm.getAlpha(i) < icm.getAlpha(i - 1)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes the heat level of every pixel, like png.js: the palette index of the legacy indexed tiles
     * (whose alpha ramp is Strava's ordered heat), the value of the grey level tiles, or the red channel of
     * painted tiles; scaled by alpha for the tiles with an alpha channel.
     * @param image tile image
     * @return heat density 0..1, row by row
     * @throws IOException if the image is not a known heat encoding
     */
    public static float[] decode(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        ColorModel cm = image.getColorModel();
        Raster raster = image.getRaster();
        float[] density = new float[width * height];
        if (cm instanceof IndexColorModel) {
            // Legacy indexed tiles must carry Strava's ordered 256-level palette: transparent to opaque
            if (!isOrderedRamp((IndexColorModel) cm)) {
                throw new IOException(tr("Unsupported heatmap density encoding. Expected Strava''s grayscale "
                        + "tiles or its ordered 256-level palette."));
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    density[y * width + x] = (float) (raster.getSample(x, y, 0) / 255.0);
                }
            }
            return density;
        }
        if (raster.getSampleModel().getSampleSize(0) != 8) {
            throw new IOException(tr("Unsupported heatmap PNG. Expected an 8-bit grayscale or indexed Strava tile."));
        }
        // The first channel carries the heat (grey level, or red of painted tiles)
        int alphaBand = cm.hasAlpha() ? raster.getNumBands() - 1 : -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int intensity = raster.getSample(x, y, 0);
                density[y * width + x] = (float) (alphaBand < 0
                        ? intensity / 255.0
                        : (intensity * raster.getSample(x, y, alphaBand)) / 65025.0);
            }
        }
        return density;
    }
}
