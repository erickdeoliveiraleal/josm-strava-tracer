// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import org.openstreetmap.josm.gui.IconToggleButton;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAction;

/**
 * Traces missing ways from the Strava heatmap imagery layer, and slides existing ways along it.
 */
public class StravaTracerPlugin extends Plugin {

    public StravaTracerPlugin(PluginInformation info) {
        super(info);
        MainMenu.add(MainApplication.getMenu().toolsMenu, new SlideAction());
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            newFrame.addMapMode(new IconToggleButton(new StravaTraceMode()));
        }
    }
}
