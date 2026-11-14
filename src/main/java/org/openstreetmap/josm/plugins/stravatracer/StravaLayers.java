// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import java.util.List;
import java.util.Locale;

import org.openstreetmap.josm.data.preferences.StringProperty;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.AbstractTileSourceLayer;

/**
 * Finds the Strava heatmap imagery layer.
 */
public final class StravaLayers {

    /**
     * Name (or part of the name) of the heatmap layer, for a layer that is not recognised from its name or URL,
     * for example a heatmap served by a proxy under another name. Empty: recognised automatically.
     */
    public static final StringProperty LAYER = new StringProperty("stravatracer.layer", "");

    /** Words of the name or URL of a heatmap layer, including the ones served through proxies. */
    private static final List<String> KEYWORDS = List.of("strava", "globalheat", "heatmap");

    private StravaLayers() {
    }

    /**
     * Returns the topmost visible imagery layer whose name contains the {@code stravatracer.layer} preference
     * when it is set, otherwise whose name or URL contains "strava", "globalheat" or "heatmap".
     * @return the layer, or {@code null}
     */
    public static AbstractTileSourceLayer<?> find() {
        String wanted = LAYER.get() == null ? "" : LAYER.get().trim().toLowerCase(Locale.ROOT);
        // The layer list is ordered from top to bottom
        for (AbstractTileSourceLayer<?> layer : MainApplication.getLayerManager()
                .getLayersOfType(AbstractTileSourceLayer.class)) {
            if (layer.isVisible() && matches(layer.getName(), layer.getInfo().getUrl(), wanted)) {
                return layer;
            }
        }
        return null;
    }

    /**
     * Tells if a layer is the heatmap layer.
     * @param name layer name
     * @param url URL template of the layer, may be {@code null}
     * @param wanted lower case name set by the user, or empty to recognise the layer automatically
     * @return {@code true} for the heatmap layer
     */
    static boolean matches(String name, String url, String wanted) {
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!wanted.isEmpty()) {
            return lowerName.contains(wanted);
        }
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return KEYWORDS.stream().anyMatch(k -> lowerName.contains(k) || lowerUrl.contains(k));
    }
}
