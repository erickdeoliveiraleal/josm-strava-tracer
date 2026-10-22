// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Result;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

/**
 * Compares the Java port of Slide with the original JavaScript, on the cases of src/test/slide-diff:
 * generate.py writes the cases, run-js.mjs the results of the original (expected.json.gz).
 */
class SlideDifferentialTest {

    /** Tolerance on the coordinates, in degrees (about 0.1 mm). */
    private static final double TOLERANCE = 1e-9;

    private static JsonObject read(String name) throws IOException {
        try (InputStream in = SlideDifferentialTest.class.getResourceAsStream("/slide/" + name);
             JsonReader reader = Json.createReader(new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8))) {
            return reader.readObject();
        }
    }

    private static double[] point(JsonValue value) {
        JsonArray a = value.asJsonArray();
        return new double[] {a.getJsonNumber(0).doubleValue(), a.getJsonNumber(1).doubleValue()};
    }

    private static List<double[]> points(JsonArray array) {
        List<double[]> result = new ArrayList<>();
        for (JsonValue v : array) {
            result.add(point(v));
        }
        return result;
    }

    private static SlideAlgorithm.Tile tile(JsonObject t) throws IOException {
        byte[] bytes;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(
                Base64.getDecoder().decode(t.getString("data"))))) {
            bytes = in.readAllBytes();
        }
        float[] density = new float[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            density[i] = (float) ((bytes[i] & 0xFF) / 255.0);
        }
        return new SlideAlgorithm.Tile(t.getInt("z"), t.getInt("x"), t.getInt("y"), t.getInt("width"),
                t.getInt("height"), density);
    }

    private static String sha256(float[] values) throws NoSuchAlgorithmException {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values) {
            buffer.putFloat(v);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(buffer.array())) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /** Compares records, returns a description of the first difference, or null. */
    private static String compare(JsonArray expected, List<Record> actual) {
        if (expected.size() != actual.size()) {
            return "size " + actual.size() + " instead of " + expected.size();
        }
        for (int i = 0; i < expected.size(); i++) {
            JsonObject e = expected.getJsonObject(i);
            Record a = actual.get(i);
            Integer nodeIndex = e.isNull("nodeIndex") ? null : e.getInt("nodeIndex");
            double[] loc = point(e.get("loc"));
            if (!java.util.Objects.equals(nodeIndex, a.nodeIndex)) {
                return "point " + i + ": nodeIndex " + a.nodeIndex + " instead of " + nodeIndex;
            }
            double d = Math.max(Math.abs(loc[0] - a.loc[0]), Math.abs(loc[1] - a.loc[1]));
            if (d > TOLERANCE) {
                return "point " + i + ": " + java.util.Arrays.toString(a.loc) + " instead of "
                        + java.util.Arrays.toString(loc) + " (" + d + "°)";
            }
        }
        return null;
    }

    private static String compareRun(JsonObject expected, java.util.function.Supplier<Result> run, String label) {
        Result result;
        try {
            result = run.get();
        } catch (SlideAlgorithm.NoSignalException e) {
            if (expected.containsKey("error") && expected.getBoolean("noSignal")) {
                return null;
            }
            return label + ": no signal (" + e.getMessage() + ") instead of " + expected;
        }
        if (expected.containsKey("error")) {
            return label + ": " + result.size() + " points instead of error " + expected.getString("error");
        }
        String diff = compare(expected.getJsonArray("records"), result);
        if (diff != null) {
            return label + ": " + diff;
        }
        if (expected.getInt("skipped") != result.skipped) {
            return label + ": skipped " + result.skipped + " instead of " + expected.getInt("skipped");
        }
        return null;
    }

    @Test
    void javaPortMatchesTheOriginal() throws Exception {
        JsonObject input = read("cases.json.gz");
        JsonArray expectedCases = read("expected.json.gz").getJsonArray("cases");
        int zoom = input.getInt("zoom");
        List<String> failures = new ArrayList<>();
        JsonArray cases = input.getJsonArray("cases");
        for (int c = 0; c < cases.size(); c++) {
            JsonObject kase = cases.getJsonObject(c);
            JsonObject expected = expectedCases.getJsonObject(c);
            String name = kase.getString("name");
            List<double[]> locations = points(kase.getJsonArray("locations"));

            List<SlideAlgorithm.Tile> tiles = new ArrayList<>();
            for (JsonValue t : kase.getJsonArray("tiles")) {
                tiles.add(tile(t.asJsonObject()));
            }
            double padding = Math.ceil((SlideAlgorithm.MAX_SEARCH_RADIUS_METERS
                    + SlideAlgorithm.CONTRAST_RADIUS_METERS) / SlideAlgorithm.metersPerPixel(locations.get(0)[1], zoom))
                    + 3;
            SlideAlgorithm.Bounds bounds = SlideAlgorithm.lineBounds(locations, zoom, padding);
            JsonObject eb = expected.getJsonObject("bounds");
            if (bounds.minX != eb.getInt("minX") || bounds.minY != eb.getInt("minY")
                    || bounds.maxX != eb.getInt("maxX") || bounds.maxY != eb.getInt("maxY")) {
                failures.add(name + ": bounds differ");
                continue;
            }
            SlideRaster raster = SlideAlgorithm.createHeatmapRaster(tiles, zoom, bounds);
            JsonObject er = expected.getJsonObject("raster");
            int covered = 0;
            for (byte b : raster.coverage) {
                covered += b;
            }
            if (raster.width != er.getInt("width") || raster.height != er.getInt("height") || raster.x != er.getInt("x")
                    || raster.y != er.getInt("y") || covered != er.getInt("covered")) {
                failures.add(name + ": raster geometry differs");
                continue;
            }
            if (!sha256(raster.density).equals(er.getString("densitySha256"))) {
                failures.add(name + ": raster density differs");
            }

            // OSM context of the vertices
            JsonArray otherWays = kase.getJsonArray("otherWays");
            List<SlideCleanup.Node> nodes = new ArrayList<>();
            JsonArray nodeInfo = kase.getJsonArray("nodes");
            for (int i = 0; i < locations.size(); i++) {
                JsonObject n = nodeInfo.getJsonObject(i);
                List<List<double[]>> others = new ArrayList<>();
                JsonArray otherIndices = n.getJsonArray("others");
                for (JsonValue k : otherIndices) {
                    JsonObject other = otherWays.getJsonObject(((jakarta.json.JsonNumber) k).intValue());
                    if (other.getBoolean("highway")) {
                        others.add(points(other.getJsonArray("locs")));
                    }
                }
                nodes.add(new SlideCleanup.Node(locations.get(i), n.getBoolean("tagged"), 1 + otherIndices.size(),
                        n.getBoolean("relation"), others));
            }
            SlideCleanup.Selection selection = new SlideCleanup.Selection(nodes, kase.getInt("start"),
                    kase.getInt("end"), kase.getInt("wayNodeCount"));
            Set<Integer> fixed = new LinkedHashSet<>();
            fixed.add(0);
            fixed.add(locations.size() - 1);
            fixed.addAll(SlideCleanup.fixedIndices(selection));
            List<Integer> expectedFixed = new ArrayList<>();
            for (JsonValue v : expected.getJsonArray("fixedIndices")) {
                expectedFixed.add(((jakarta.json.JsonNumber) v).intValue());
            }
            if (!new ArrayList<>(fixed).equals(expectedFixed)) {
                failures.add(name + ": fixed indices " + fixed + " instead of " + expectedFixed);
            }
            SlideAlgorithm.Options options = new SlideAlgorithm.Options();
            options.fixedIndices = fixed;
            for (JsonValue o : otherWays) {
                if (o.asJsonObject().getBoolean("highway")) {
                    options.otherWays.add(points(o.asJsonObject().getJsonArray("locs")));
                }
            }

            String once = compareRun(expected.getJsonObject("once"),
                    () -> SlideAlgorithm.slideLine(locations, raster, options), "slideLine");
            if (once != null) {
                failures.add(name + ": " + once);
            }
            Result[] twice = new Result[1];
            String twiceDiff = compareRun(expected.getJsonObject("twice"),
                    () -> twice[0] = SlideAlgorithm.slideLineTwice(locations, raster, options), "slideLineTwice");
            if (twiceDiff != null) {
                failures.add(name + ": " + twiceDiff);
            } else if (twice[0] != null && expected.containsKey("cleaned")) {
                Result cleaned = SlideCleanup.cleanSlideResult(selection, twice[0]);
                JsonObject ec = expected.getJsonObject("cleaned");
                String diff = compare(ec.getJsonArray("records"), cleaned);
                if (diff != null) {
                    failures.add(name + ": cleanSlideResult: " + diff);
                } else if (cleaned.alongOther != ec.getInt("alongOther")) {
                    failures.add(name + ": cleanSlideResult: alongOther " + cleaned.alongOther);
                }
            }
        }
        assertTrue(failures.isEmpty(), cases.size() + " cases, differences:\n" + String.join("\n", failures));
    }
}
