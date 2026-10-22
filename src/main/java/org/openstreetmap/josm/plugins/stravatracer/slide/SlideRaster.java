// License: MIT. Port of strava-heatmap-extension by Julien ETIENNE (julcnx). For details, see the NOTICE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

/**
 * A rectangle of heat density (0..1) and coverage samples in projected pixel space
 * (Web Mercator pixels of 512 pixel tiles at {@link #zoom}).
 * A global point {@code p} maps to raster-local coordinates {@code (p[0] - x, p[1] - y)}.
 */
public final class SlideRaster {
    public final int width;
    public final int height;
    public final int x;
    public final int y;
    public final int zoom;
    /** Heat density, row by row. */
    public final float[] density;
    /** Whether a pixel is backed by real imagery (1) or not (0). */
    public final byte[] coverage;
    /** Zones next to other mapped ways (see maskOtherWays), or {@code null}. */
    public final byte[] near;
    /** Raw density of a tracking surface (see trackingSurface), or {@code null}. */
    public final float[] heat;

    public SlideRaster(int width, int height, int x, int y, int zoom, float[] density, byte[] coverage,
            byte[] near, float[] heat) {
        this.width = width;
        this.height = height;
        this.x = x;
        this.y = y;
        this.zoom = zoom;
        this.density = density;
        this.coverage = coverage;
        this.near = near;
        this.heat = heat;
    }

    SlideRaster withDensity(float[] newDensity, byte[] newNear, float[] newHeat) {
        return new SlideRaster(width, height, x, y, zoom, newDensity, coverage, newNear, newHeat);
    }
}
