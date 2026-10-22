// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.openstreetmap.gui.jmapviewer.tilesources.AbstractTMSTileSource;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.gui.layer.TMSLayer;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

/**
 * A square of heatmap tiles around a point, converted to intensities.
 * Pixel coordinates are relative to the top left corner of the window, in the Web Mercator
 * pixel grid of the tile zoom level.
 */
public final class HeatmapWindow {

    private static final double EARTH_CIRCUMFERENCE = 40075016.686;
    private static final int TILE_CACHE_SIZE = 100;

    /** Recently downloaded tiles, by URL. */
    private static final Map<String, BufferedImage> TILE_CACHE =
            new LinkedHashMap<String, BufferedImage>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
            return size() > TILE_CACHE_SIZE;
        }
    };

    private final int zoom;
    private final int tileSize;
    private final long originX;
    private final long originY;
    private final int[][] intensity;
    /** Raw colours of the tiles, for the diagnostics. */
    private int[][] argb;
    /** Download log, for the diagnostics. */
    private final List<String> log = new ArrayList<>();

    HeatmapWindow(int zoom, int tileSize, long originX, long originY, int[][] intensity) {
        this.zoom = zoom;
        this.tileSize = tileSize;
        this.originX = originX;
        this.originY = originY;
        this.intensity = intensity;
    }

    /**
     * Downloads the tiles of the heatmap layer around a point.
     * @param info imagery of the Strava layer (its cookies and HTTP headers are reused)
     * @param zoom tile zoom level
     * @param center centre of the window
     * @param radius number of tiles on each side of the central tile
     * @param keepColours keep the raw colours of the tiles, for the diagnostics
     * @return the window
     * @throws IOException if a tile cannot be downloaded
     */
    public static HeatmapWindow load(ImageryInfo info, int zoom, LatLon center, int radius, boolean keepColours)
            throws IOException {
        AbstractTMSTileSource source = TMSLayer.getTileSourceStatic(info);
        boolean hot = info.getUrl().toLowerCase(Locale.ROOT).contains("/hot/");
        int n = 1 << zoom;
        int centerX = (int) Math.floor(lonToTile(center.lon(), zoom));
        int centerY = (int) Math.floor(latToTile(center.lat(), zoom));
        int size = 2 * radius + 1;

        BufferedImage[][] tiles = new BufferedImage[size][size];
        List<String> log = new ArrayList<>();
        log.add("URL template: " + withoutQuery(info.getUrl()) + " (hot palette: " + hot + ")");
        log.add("Cookies: " + (info.getCookies() == null || info.getCookies().isEmpty() ? "none" : "yes")
                + ", custom headers: " + info.getCustomHttpHeaders().keySet());
        int tileSize = -1;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                int tx = Math.floorMod(centerX - radius + i, n);
                int ty = centerY - radius + j;
                if (ty < 0 || ty >= n) {
                    continue;
                }
                String url = source.getTileUrl(zoom, tx, ty);
                tiles[j][i] = cachedDownload(info, url, log);
                if (tiles[j][i] != null && tileSize < 0) {
                    tileSize = tiles[j][i].getWidth();
                }
            }
        }
        if (tileSize < 0) {
            tileSize = source.getTileSize();
        }

        int[][] intensity = new int[size * tileSize][size * tileSize];
        int[][] argb = keepColours ? new int[size * tileSize][size * tileSize] : null;
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                BufferedImage tile = tiles[j][i];
                if (tile == null) {
                    continue;
                }
                // The new Strava tiles are grey levels, whatever the URL says
                boolean tileHot = hot && tile.getColorModel().getColorSpace().getNumComponents() > 1;
                // Tiles with a different size (should not happen) are stretched
                for (int y = 0; y < tileSize; y++) {
                    for (int x = 0; x < tileSize; x++) {
                        int c = tile.getRGB(x * tile.getWidth() / tileSize, y * tile.getHeight() / tileSize);
                        if (argb != null) {
                            argb[j * tileSize + y][i * tileSize + x] = c;
                        }
                        intensity[j * tileSize + y][i * tileSize + x] = StravaPalette.intensity(c, tileHot);
                    }
                }
            }
        }
        HeatmapWindow window = new HeatmapWindow(zoom, tileSize, (long) (centerX - radius) * tileSize,
                (long) (centerY - radius) * tileSize, intensity);
        window.argb = argb;
        window.log.addAll(log);
        return window;
    }

    /** Removes the query string, which may contain credentials. */
    static String withoutQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }

    /** Downloads a tile, or returns it from the cache when the window is enlarged. */
    private static BufferedImage cachedDownload(ImageryInfo info, String url, List<String> log) throws IOException {
        synchronized (TILE_CACHE) {
            if (TILE_CACHE.containsKey(url)) {
                return TILE_CACHE.get(url);
            }
        }
        BufferedImage image = download(info, url, log);
        synchronized (TILE_CACHE) {
            TILE_CACHE.put(url, image);     // null for the tiles without activity
        }
        return image;
    }

    private static BufferedImage download(ImageryInfo info, String url, List<String> log) throws IOException {
        HttpClient client = HttpClient.create(new URL(url));
        for (Map.Entry<String, String> header : info.getCustomHttpHeaders().entrySet()) {
            client.setHeader(header.getKey(), header.getValue());
        }
        if (info.getCookies() != null && !info.getCookies().isEmpty()) {
            client.setHeader("Cookie", info.getCookies());
        }
        try {
            HttpClient.Response response = client.connect();
            int code = response.getResponseCode();
            log.add(withoutQuery(url) + " -> HTTP " + code + " " + response.getContentType());
            if (code == 404 || code == 204) {
                return null;     // No activity in this tile
            }
            if (code == 401 || code == 403) {
                throw new IOException(tr("Access to the Strava heatmap was refused (HTTP {0}). "
                        + "The cookies of the imagery layer may have expired.", code));
            }
            if (code != 200) {
                throw new IOException(tr("Cannot download {0} (HTTP {1})", url, code));
            }
            try (InputStream in = response.getContent()) {
                BufferedImage image = ImageIO.read(in);
                if (image == null) {
                    Logging.warn("Strava tracer: invalid image " + withoutQuery(url));
                    log.add("   invalid image");
                } else {
                    log.add("   " + image.getWidth() + "x" + image.getHeight() + " type " + image.getType()
                            + " alpha " + image.getColorModel().hasAlpha());
                }
                return image;
            }
        } finally {
            client.disconnect();
        }
    }

    private static double lonToTile(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (1 << zoom);
    }

    private static double latToTile(double lat, int zoom) {
        double rad = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * (1 << zoom);
    }

    /** @return intensities, indexed as {@code [y][x]} */
    public int[][] getIntensity() {
        return intensity;
    }

    /** @return raw colours of the tiles, indexed as {@code [y][x]} (may be {@code null}) */
    public int[][] getArgb() {
        return argb;
    }

    /** @return download log */
    public List<String> getLog() {
        return log;
    }

    public int getZoom() {
        return zoom;
    }

    public int getTileSize() {
        return tileSize;
    }

    public int getWidth() {
        return intensity[0].length;
    }

    public int getHeight() {
        return intensity.length;
    }

    /**
     * Converts a geographical position to window pixel coordinates.
     * @param ll position
     * @return pixel coordinates (may be outside the window)
     */
    public Point2D.Double toPixel(LatLon ll) {
        return new Point2D.Double(lonToTile(ll.lon(), zoom) * tileSize - originX,
                latToTile(ll.lat(), zoom) * tileSize - originY);
    }

    /**
     * Converts window pixel coordinates to a geographical position.
     * The centre of the pixel is used.
     * @param x column
     * @param y row
     * @return position
     */
    public LatLon toLatLon(double x, double y) {
        double worldSize = (double) tileSize * (1 << zoom);
        double gx = (originX + x + 0.5) / worldSize;
        double gy = (originY + y + 0.5) / worldSize;
        double lon = gx * 360.0 - 180.0;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * gy))));
        return new LatLon(lat, lon);
    }

    /**
     * Returns the ground size of a pixel.
     * @param lat latitude
     * @return size of a pixel in meters
     */
    public double metersPerPixel(double lat) {
        return EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians(lat)) / ((double) tileSize * (1 << zoom));
    }
}
