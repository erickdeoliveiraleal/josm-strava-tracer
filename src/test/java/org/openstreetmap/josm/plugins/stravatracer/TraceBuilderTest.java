// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.spi.preferences.Config;

class TraceBuilderTest {

    private static final int SIZE = 768;

    @BeforeAll
    static void setUpJosm() {
        Config.setPreferencesInstance(new Preferences(JosmBaseDirectories.getInstance()));
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        Config.setUrlsProvider(JosmUrls.getInstance());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    /** Window of 3x3 tiles of 256 pixels at zoom 15, in Brasília. */
    private static HeatmapWindow window() {
        return new HeatmapWindow(15, 256, 12022L * 256, 17839L * 256, new int[SIZE][SIZE]);
    }

    private static Node node(DataSet ds, HeatmapWindow w, double x, double y) {
        Node n = new Node(w.toLatLon(x, y));
        ds.addPrimitive(n);
        return n;
    }

    @Test
    void freeEndsAreConnectedToTheExistingWays() {
        HeatmapWindow w = window();
        DataSet ds = new DataSet();
        // Existing vertical road at x=100, with nodes far from the trace end
        Node a = node(ds, w, 100, 50);
        Node b = node(ds, w, 100, 700);
        Way road = new Way();
        road.setNodes(List.of(a, b));
        road.put("highway", "residential");
        ds.addPrimitive(road);
        // Existing footway ending at (600, 300), right where the trace ends
        Node c = node(ds, w, 601, 300);
        Node d = node(ds, w, 750, 300);
        Way footway = new Way();
        footway.setNodes(List.of(c, d));
        footway.put("highway", "footway");
        ds.addPrimitive(footway);

        // The trace stops at the edge of the masked area, ~6 pixels (15 m) away from both ways
        List<List<Point>> lines = List.of(List.of(new Point(106, 300), new Point(350, 320), new Point(594, 300)));
        List<Way> created = new ArrayList<>();
        Command cmd = TraceBuilder.build(ds, ds.getWays(), lines, w, 40, Map.of("highway", "path"), created);
        assertNotNull(cmd);
        cmd.executeCommand();

        assertEquals(1, created.size());
        Way path = created.get(0);
        assertEquals("path", path.get("highway"));
        assertEquals(5, path.getNodesCount(), path.getNodes().toString());
        // Start: new node inserted in the road
        Node start = path.firstNode();
        assertEquals(3, road.getNodesCount());
        assertSame(start, road.getNode(1));
        // On the road (pixel centres are at +0.5), level with the end of the trace
        assertEquals(100.5, w.toPixel(start.getCoor()).x, 0.01);
        assertEquals(300.5, w.toPixel(start.getCoor()).y, 0.01);
        // End: the existing node of the footway is reused
        assertSame(c, path.lastNode());
        assertEquals(2, footway.getNodesCount());

        cmd.undoCommand();
        assertEquals(2, road.getNodesCount());
        assertFalse(ds.containsWay(path));
    }

    @Test
    void endsFarFromAnyWayAreLeftFree() {
        HeatmapWindow w = window();
        DataSet ds = new DataSet();
        List<List<Point>> lines = List.of(List.of(new Point(300, 300), new Point(400, 400)));
        List<Way> created = new ArrayList<>();
        Command cmd = TraceBuilder.build(ds, ds.getWays(), lines, w, 40, Map.of("highway", "path"), created);
        cmd.executeCommand();
        assertEquals(2, created.get(0).getNodesCount());
    }

    @Test
    void junctionNodeIsShared() {
        HeatmapWindow w = window();
        DataSet ds = new DataSet();
        Point junction = new Point(400, 400);
        List<List<Point>> lines = List.of(
                List.of(new Point(300, 300), junction),
                List.of(junction, new Point(500, 300)),
                List.of(junction, new Point(400, 550)));
        List<Way> created = new ArrayList<>();
        TraceBuilder.build(ds, ds.getWays(), lines, w, 40, Map.of("highway", "path"), created).executeCommand();
        assertEquals(3, created.size());
        Node shared = created.get(0).lastNode();
        assertSame(shared, created.get(1).firstNode());
        assertSame(shared, created.get(2).firstNode());
    }

    @Test
    void branchIsConnectedToTheMiddleOfTheMainLine() {
        HeatmapWindow w = window();
        DataSet ds = new DataSet();
        Point junction = new Point(400, 400);
        List<List<Point>> lines = List.of(
                List.of(new Point(300, 300), junction, new Point(500, 300)),
                List.of(junction, new Point(400, 550)));
        List<Way> created = new ArrayList<>();
        TraceBuilder.build(ds, ds.getWays(), lines, w, 40, Map.of("highway", "path"), created).executeCommand();
        assertEquals(2, created.size());
        assertSame(created.get(0).getNode(1), created.get(1).firstNode());
    }

    @Test
    void traceEndingAtTheBorderTriggersALargerWindow() {
        HeatmapWindow w = window();
        assertTrue(StravaTraceMode.reachesBorder(List.of(List.of(new Point(300, 300), new Point(SIZE - 2, 400))), w));
        assertTrue(StravaTraceMode.reachesBorder(List.of(List.of(new Point(1, 300), new Point(400, 400))), w));
        assertFalse(StravaTraceMode.reachesBorder(List.of(List.of(new Point(300, 300), new Point(700, 400))), w));
        // Inner points on the border do not matter, only the ends
        assertFalse(StravaTraceMode.reachesBorder(
                List.of(List.of(new Point(300, 300), new Point(SIZE - 1, 350), new Point(600, 400))), w));
    }

    @Test
    void maskCoversTheExistingWays() {
        HeatmapWindow w = window();
        DataSet ds = new DataSet();
        Way road = new Way();
        road.setNodes(List.of(node(ds, w, 100, 50), node(ds, w, 100, 700)));
        road.put("highway", "residential");
        ds.addPrimitive(road);
        Way building = new Way();
        building.setNodes(List.of(node(ds, w, 300, 300), node(ds, w, 350, 300)));
        building.put("building", "yes");
        ds.addPrimitive(building);

        // 15 m at zoom 15 in Brasília is about 3.2 pixels of 256 pixel tiles
        boolean[][] mask = TraceBuilder.mask(ds.getWays(), w, 15);
        assertTrue(mask[400][100]);
        assertTrue(mask[400][103]);
        assertFalse(mask[400][106]);
        assertFalse(mask[300][320], "non highway ways must not be masked");
    }
}
