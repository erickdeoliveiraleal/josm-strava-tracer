// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import java.util.Locale;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;

/**
 * Finds the Strava heatmap imagery layer.
 */
public final class StravaLayers {

    private StravaLayers() {
    }

    /**
     * Returns the topmost visible imagery layer whose name or URL contains "strava".
     * @return the layer, or {@code null}
     */
    public static AbstractTileSourceLayer<?> find() {
        // The layer list is ordered from top to bottom
        for (AbstractTileSourceLayer<?> layer : MainApplication.getLayerManager()
                .getLayersOfType(AbstractTileSourceLayer.class)) {
            String url = layer.getInfo().getUrl();
            if (layer.isVisible() && (layer.getName().toLowerCase(Locale.ROOT).contains("strava")
                    || (url != null && url.toLowerCase(Locale.ROOT).contains("strava")))) {
                return layer;
            }
        }
        return null;
    }
}
