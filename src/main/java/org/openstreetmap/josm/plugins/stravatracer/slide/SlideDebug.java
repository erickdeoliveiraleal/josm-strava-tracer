// License: GPL v3 or later. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.stravatracer.slide;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.openstreetmap.josm.plugins.stravatracer.slide.SlideAlgorithm.Record;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Writes a failed Slide run as a test case, in the format of src/test/slide-diff/generate.py, so that it can
 * be replayed with the original JavaScript (run-js.mjs) and the Java port (SlideDifferentialTest).
 */
final class SlideDebug {

    /** A way near the slid line. */
    static final class OtherWay {
        final boolean highway;
        final List<double[]> locs;

        OtherWay(boolean highway, List<double[]> locs) {
            this.highway = highway;
            this.locs = locs;
        }
    }

    private SlideDebug() {
    }

    /**
     * Writes the case.
     * @return the written file
     */
    static File write(String error, List<SlideAlgorithm.Tile> tiles, SlideCleanup.Selection selection,
            List<List<Integer>> nodeOthers, List<OtherWay> otherWays, List<Record> result) throws IOException {
        File dir = new File(Config.getDirs().getUserDataDirectory(true), "stravatracer-debug");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File file = new File(dir, "slide-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".json.gz");
        StringBuilder json = new StringBuilder();
        json.append("{\"zoom\":").append(SlideTiles.SLIDE_ZOOM).append(",\"cases\":[{\"name\":")
            .append(quote("josm-" + file.getName())).append(",\"error\":").append(quote(error));
        json.append(",\"tiles\":[");
        for (int t = 0; t < tiles.size(); t++) {
            SlideAlgorithm.Tile tile = tiles.get(t);
            byte[] levels = new byte[tile.density.length];
            for (int i = 0; i < levels.length; i++) {
                levels[i] = (byte) Math.round(tile.density[i] * 255);
            }
            json.append(t > 0 ? "," : "").append("{\"z\":").append(tile.z).append(",\"x\":").append(tile.x)
                .append(",\"y\":").append(tile.y).append(",\"width\":").append(tile.width).append(",\"height\":")
                .append(tile.height).append(",\"data\":").append(quote(Base64.getEncoder().encodeToString(gzip(levels))))
                .append('}');
        }
        json.append("],\"locations\":[");
        for (int i = 0; i < selection.nodes.size(); i++) {
            json.append(i > 0 ? "," : "").append(point(selection.nodes.get(i).loc));
        }
        json.append("],\"nodes\":[");
        for (int i = 0; i < selection.nodes.size(); i++) {
            SlideCleanup.Node n = selection.nodes.get(i);
            json.append(i > 0 ? "," : "").append("{\"tagged\":").append(n.tagged).append(",\"relation\":")
                .append(n.relationMember).append(",\"others\":").append(nodeOthers.get(i)).append('}');
        }
        json.append("],\"otherWays\":[");
        for (int k = 0; k < otherWays.size(); k++) {
            json.append(k > 0 ? "," : "").append("{\"highway\":").append(otherWays.get(k).highway).append(",\"locs\":[");
            List<double[]> locs = otherWays.get(k).locs;
            for (int i = 0; i < locs.size(); i++) {
                json.append(i > 0 ? "," : "").append(point(locs.get(i)));
            }
            json.append("]}");
        }
        json.append("],\"start\":").append(selection.start).append(",\"end\":").append(selection.end)
            .append(",\"wayNodeCount\":").append(selection.wayNodeCount);
        if (result != null) {
            json.append(",\"javaResult\":[");
            for (int i = 0; i < result.size(); i++) {
                Record r = result.get(i);
                json.append(i > 0 ? "," : "").append("{\"loc\":").append(point(r.loc)).append(",\"nodeIndex\":")
                    .append(r.nodeIndex).append('}');
            }
            json.append(']');
        }
        json.append("}]}");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file.toPath()));
             Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        }
        return file;
    }

    private static String point(double[] p) {
        return "[" + p[0] + "," + p[1] + "]";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : String.valueOf(s).toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(data);
        }
        return bytes.toByteArray();
    }
}
