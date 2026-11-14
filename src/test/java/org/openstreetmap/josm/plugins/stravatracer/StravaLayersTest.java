// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StravaLayersTest {

    /** Heatmap layers are recognised from their name or URL, including through proxies. */
    @Test
    void heatmapLayersAreRecognised() {
        assertTrue(StravaLayers.matches("Strava cycling heatmap", null, ""));
        assertTrue(StravaLayers.matches("Nakarte", "https://proxy.nakarte.me/https/content-a.strava.com/identified/"
                + "globalheat/all/hot/{zoom}/{x}/{y}.png?px=256", ""));
        assertTrue(StravaLayers.matches("My proxy", "https://example.org/globalheat/all/blue/{zoom}/{x}/{y}.png", ""));
        assertTrue(StravaLayers.matches("Heatmap (proxy)", "https://example.org/tiles/{zoom}/{x}/{y}.png", ""));
        assertFalse(StravaLayers.matches("Bing aerial imagery", "bing", ""));
        assertFalse(StravaLayers.matches("Esri World Imagery", "https://server.arcgisonline.com/{zoom}/{y}/{x}", ""));
    }

    /** The stravatracer.layer preference chooses a layer whose name or URL says nothing of a heatmap. */
    @Test
    void preferredLayerIsChosenByName() {
        assertTrue(StravaLayers.matches("Activity tiles", "https://example.org/t/{zoom}/{x}/{y}.png", "activity"));
        // Once set, only that name counts
        assertFalse(StravaLayers.matches("Strava heatmap", "https://content-a.strava.com/x", "activity"));
    }
}
