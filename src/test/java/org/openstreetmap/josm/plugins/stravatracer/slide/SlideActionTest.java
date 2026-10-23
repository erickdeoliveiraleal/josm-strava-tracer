// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.preferences.JosmUrls;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;
import org.openstreetmap.josm.spi.preferences.Config;

class SlideActionTest {

    private static final int Z = 15;

    @BeforeAll
    static void setUpJosm() {
        Config.setPreferencesInstance(new Preferences(JosmBaseDirectories.getInstance()));
        Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
        Config.setUrlsProvider(JosmUrls.getInstance());
        ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
    }

    private static Node node(DataSet ds, double x, double y) {
        double[] loc = SlideAlgorithm.unproject(new double[] {x, y}, Z);
        Node n = new Node(new LatLon(loc[1], loc[0]));
        ds.addPrimitive(n);
        return n;
    }

    private static Way way(DataSet ds, List<Node> nodes, String highway) {
        Way w = new Way();
        w.setNodes(nodes);
        w.put("highway", highway);
        ds.addPrimitive(w);
        return w;
    }

    @Test
    void selectionOfAWayOrOfARange() {
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            nodes.add(node(ds, 6000000 + i * 40, 9000000 + (i % 2) * 10));
        }
        Way path = way(ds, nodes, "path");
        SlideAction.Target whole = SlideAction.target(Set.of(path));
        assertNotNull(whole);
        assertEquals(0, whole.start);
        assertEquals(5, whole.end);
        SlideAction.Target range = SlideAction.target(List.of(nodes.get(4), nodes.get(1), nodes.get(2)));
        assertNotNull(range);
        assertEquals(1, range.start);
        assertEquals(4, range.end);
        assertEquals(4, range.nodes.size());
        // One node, or nodes on two ways, is ambiguous
        assertNull(SlideAction.target(List.of(nodes.get(1))));
        Way other = way(ds, List.of(nodes.get(2), node(ds, 6000100, 9000200)), "track");
        assertNull(SlideAction.target(List.<OsmPrimitive>of(path, other)));
        assertNull(SlideAction.target(List.of(nodes.get(1), other.getNode(1))), "not on one common way");
        // Two nodes of the other way: its range
        assertSame(other, SlideAction.target(List.of(nodes.get(2), other.getNode(1))).way);
        // Structures are refused
        path.put("bridge", "yes");
        assertTrue(SlideAction.isStructure(path));
        path.put("bridge", "no");
        assertFalse(SlideAction.isStructure(path));
    }

    @Test
    void osmContextOfTheRange() {
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(node(ds, 6000000 + i * 40, 9000000));
        }
        Way path = way(ds, nodes, "path");
        nodes.get(1).put("barrier", "gate");
        Way road = way(ds, List.of(node(ds, 6000080, 8999900), nodes.get(2), node(ds, 6000080, 9000100)), "residential");
        Way fence = new Way();
        fence.setNodes(List.of(nodes.get(3), node(ds, 6000150, 9000050)));
        fence.put("barrier", "fence");
        ds.addPrimitive(fence);
        Relation route = new Relation();
        route.addMember(new RelationMember("", nodes.get(4)));
        ds.addPrimitive(route);

        SlideAction.Target target = SlideAction.target(List.of(nodes.get(1), nodes.get(4)));
        SlideCleanup.Selection selection = SlideAction.selection(target);
        assertEquals(4, selection.nodes.size());
        assertTrue(selection.nodes.get(0).tagged);
        assertEquals(2, selection.nodes.get(1).parentWays);
        assertEquals(1, selection.nodes.get(1).otherHighways.size(), "the road is a highway");
        assertEquals(3, selection.nodes.get(1).otherHighways.get(0).size());
        assertEquals(0, selection.nodes.get(2).otherHighways.size(), "a fence has no heat of its own");
        assertTrue(selection.nodes.get(3).relationMember);
        // Partial range: both boundaries are fixed, and so are the protected nodes
        assertEquals(Set.of(0, 1, 2, 3), SlideCleanup.fixedIndices(selection));
        assertSame(road, nodes.get(2).getParentWays().stream().filter(w -> w != path && w.hasKey("highway"))
                .findFirst().get());
    }

    @Test
    void commandMovesAddsAndDeletesNodes() {
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            nodes.add(node(ds, 6000000 + i * 40, 9000000));
        }
        Way path = way(ds, nodes, "path");
        SlideAction.Target target = SlideAction.target(Set.of(path));
        SlideCleanup.Selection selection = SlideAction.selection(target);
        // Keep 0, move 1, drop 2 and 3, add a new point, keep 4
        List<Record> result = new ArrayList<>();
        result.add(new Record(selection.nodes.get(0).loc, 0));
        result.add(new Record(SlideAlgorithm.unproject(new double[] {6000040, 9000005}, Z), 1));
        result.add(new Record(SlideAlgorithm.unproject(new double[] {6000110, 9000008}, Z), null));
        result.add(new Record(selection.nodes.get(4).loc, 4));
        Command command = SlideAction.createCommand(ds, target, selection, result);
        command.executeCommand();
        assertEquals(4, path.getNodesCount());
        assertSame(nodes.get(0), path.getNode(0));
        assertSame(nodes.get(1), path.getNode(1));
        assertTrue(path.getNode(2).isNew());
        assertSame(nodes.get(4), path.getNode(3));
        assertEquals(9000005, SlideAlgorithm.project(new double[] {nodes.get(1).lon(), nodes.get(1).lat()}, Z)[1], 1e-6);
        assertTrue(nodes.get(2).isDeleted());
        assertTrue(nodes.get(3).isDeleted());
        command.undoCommand();
        assertEquals(nodes, path.getNodes());
        assertFalse(nodes.get(2).isDeleted());
        assertEquals(9000000, SlideAlgorithm.project(new double[] {nodes.get(1).lon(), nodes.get(1).lat()}, Z)[1], 1e-6);
    }

    @Test
    void commandRefusesToMoveOrDropProtectedNodes() {
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            nodes.add(node(ds, 6000000 + i * 40, 9000000));
        }
        Way path = way(ds, nodes, "path");
        nodes.get(1).put("barrier", "gate");
        SlideAction.Target target = SlideAction.target(Set.of(path));
        SlideCleanup.Selection selection = SlideAction.selection(target);
        List<Record> moved = new ArrayList<>();
        moved.add(new Record(selection.nodes.get(0).loc, 0));
        moved.add(new Record(SlideAlgorithm.unproject(new double[] {6000040, 9000009}, Z), 1));
        moved.add(new Record(selection.nodes.get(3).loc, 3));
        assertThrows(IllegalStateException.class, () -> SlideAction.createCommand(ds, target, selection, moved));
        List<Record> dropped = new ArrayList<>();
        dropped.add(new Record(selection.nodes.get(0).loc, 0));
        dropped.add(new Record(selection.nodes.get(3).loc, 3));
        assertThrows(IllegalStateException.class, () -> SlideAction.createCommand(ds, target, selection, dropped));
    }

    /** Draws a horizontal trail of heat in a zoom 15 tile, and slides a zigzag line drawn along it. */
    @Test
    void endToEndSlideOfARoughLine() {
        int tx = 12704;
        int ty = 17698;
        float[] density = new float[512 * 512];
        for (int x = 0; x < 512; x++) {
            for (int dy = -2; dy <= 2; dy++) {
                density[(256 + dy) * 512 + x] = (float) ((dy == 0 ? 200 : Math.abs(dy) == 1 ? 140 : 60) / 255.0);
            }
        }
        List<SlideAlgorithm.Tile> tiles = new ArrayList<>();
        for (int x = tx - 1; x <= tx + 1; x++) {
            for (int y = ty - 1; y <= ty + 1; y++) {
                tiles.add(new SlideAlgorithm.Tile(Z, x, y, 512, 512, x == tx && y == ty ? density
                        : y == ty ? density : new float[512 * 512]));
            }
        }
        DataSet ds = new DataSet();
        List<Node> nodes = new ArrayList<>();
        double baseX = tx * 512.0;
        double baseY = ty * 512 + 256.5;
        for (int i = 0; i <= 8; i++) {
            nodes.add(node(ds, baseX + 60 + i * 45, baseY + (i == 0 || i == 8 ? 0 : (i % 2 == 0 ? 4 : -4))));
        }
        Way path = way(ds, nodes, "path");
        SlideAction.Target target = SlideAction.target(Set.of(path));
        SlideCleanup.Selection selection = SlideAction.selection(target);
        List<double[]> locations = new ArrayList<>();
        for (SlideCleanup.Node n : selection.nodes) {
            locations.add(n.loc);
        }
        double padding = Math.ceil((SlideAlgorithm.MAX_SEARCH_RADIUS_METERS + SlideAlgorithm.CONTRAST_RADIUS_METERS)
                / SlideAlgorithm.metersPerPixel(locations.get(0)[1], Z)) + 3;
        SlideRaster raster = SlideAlgorithm.createHeatmapRaster(tiles, Z,
                SlideAlgorithm.lineBounds(locations, Z, padding));
        SlideAlgorithm.Options options = new SlideAlgorithm.Options();
        options.fixedIndices.add(0);
        options.fixedIndices.add(locations.size() - 1);
        Result result = SlideCleanup.cleanSlideResult(selection,
                SlideAlgorithm.slideLineTwice(locations, raster, options));
        Command command = SlideAction.createCommand(ds, target, selection, result);
        command.executeCommand();
        // The zigzag is gone: every point of the slid line is on the trail (within half a pixel)
        for (Node n : path.getNodes()) {
            double y = SlideAlgorithm.project(new double[] {n.lon(), n.lat()}, Z)[1];
            assertEquals(baseY, y, 1.0, "point " + n + " is off the trail");
        }
        assertTrue(path.getNodesCount() < nodes.size(), "the straight trail needs fewer points");
        assertSame(nodes.get(0), path.firstNode());
        assertSame(nodes.get(8), path.lastNode());
        command.undoCommand();
        assertEquals(nodes, path.getNodes());
    }

    @Test
    void longLinesAreRefusedOnlyWithoutEnoughMemory() throws Exception {
        // A 17 km diagonal road: 72 tiles, 15 million pixels (refused by the original, at 16 tiles)
        SlideTiles.checkMemory(1_000_000, 16);
        assertTrue(72 <= SlideTiles.MAX_TILES);
        assertTrue(15_400_000 <= SlideAlgorithm.MAX_RASTER_PIXELS);
        // More than the memory left: a clear message instead of an OutOfMemoryError
        java.io.IOException e = assertThrows(java.io.IOException.class,
                () -> SlideTiles.checkMemory(Runtime.getRuntime().maxMemory(), 144));
        assertTrue(e.getMessage().contains("memory"), e.getMessage());
    }

    @Test
    void decodesIndexedAndGreyTiles() throws Exception {
        // Indexed tile with Strava's ordered alpha ramp: the index is the heat
        byte[] r = new byte[256];
        byte[] g = new byte[256];
        byte[] b = new byte[256];
        byte[] a = new byte[256];
        for (int i = 0; i < 256; i++) {
            r[i] = (byte) 255;
            g[i] = (byte) (255 - i);
            a[i] = (byte) i;
        }
        BufferedImage indexed = new BufferedImage(4, 1, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 256, r, g, b, a));
        int[] levels = {0, 1, 128, 255};
        for (int x = 0; x < 4; x++) {
            indexed.getRaster().setSample(x, 0, 0, levels[x]);
        }
        float[] density = SlideTiles.decode(roundTrip(indexed));
        for (int x = 0; x < 4; x++) {
            assertEquals((float) (levels[x] / 255.0), density[x]);
        }
        // Grey level tile (the current Strava tiles): the value is the heat
        BufferedImage grey = new BufferedImage(4, 1, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < 4; x++) {
            grey.getRaster().setSample(x, 0, 0, levels[x]);
        }
        density = SlideTiles.decode(roundTrip(grey));
        for (int x = 0; x < 4; x++) {
            assertEquals((float) (levels[x] / 255.0), density[x]);
        }
        // An arbitrary palette is not a heat encoding
        a[255] = 100;
        BufferedImage other = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 256, r, g, b, a));
        assertThrows(java.io.IOException.class, () -> SlideTiles.decode(roundTrip(other)));
    }

    private static BufferedImage roundTrip(BufferedImage image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }
}
