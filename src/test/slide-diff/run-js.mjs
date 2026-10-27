// Run the original JavaScript Slide (upstream/, strava-heatmap-extension v0.15.0, MIT) on the cases of
// generate.py, and write the expected results compared by SlideDifferentialTest:
//
//   node run-js.mjs [cases.json.gz [expected.json.gz]]
//
// Without arguments, the cases of generate.py are used. A diagnostics file written by the plugin when Slide
// fails (stravatracer-debug/slide-*.json.gz) can be given instead, to replay that run.
//
// The pipeline mirrors slide/index.js: padded line bounds (tiles.js), createHeatmapRaster,
// fixed indices from the OSM context (action.js), other highways, slideLineTwice, cleanSlideResult.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';
import {
  createHeatmapRaster,
  lineBounds,
  metersPerPixel,
  SLIDE_DEFAULTS,
  slideLine,
  slideLineTwice,
} from './upstream/algorithm.js';
import { cleanSlideResult, fixedIndices } from './upstream/action.js';

const here = path.dirname(fileURLToPath(import.meta.url));
const resources = path.join(here, '..', 'resources', 'slide');
const inputPath = process.argv[2] || path.join(resources, 'cases.json.gz');
const outputPath = process.argv[3] || path.join(resources, 'expected.json.gz');
const input = JSON.parse(zlib.gunzipSync(fs.readFileSync(inputPath)));

// Same decoding as png.js: heat level / 255 in a Float32Array.
function decodeTile(tile) {
  const bytes = zlib.gunzipSync(Buffer.from(tile.data, 'base64'));
  const density = new Float32Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) density[i] = bytes[i] / 255;
  return { z: tile.z, x: tile.x, y: tile.y, width: tile.width, height: tile.height, density };
}

// Minimal iD graph for action.js: the selected way, its nodes, the other ways and relations.
function buildGraph(c) {
  const nodes = c.locations.map((loc, i) => ({
    id: `n${i}`,
    loc,
    tags: c.nodes[i].tagged ? { barrier: 'gate' } : {},
  }));
  const wayNodes = [];
  for (let i = 0; i < c.start; i++) wayNodes.push(`before${i}`);
  wayNodes.push(...nodes.map((n) => n.id));
  while (wayNodes.length < c.wayNodeCount) wayNodes.push(`after${wayNodes.length}`);
  const way = { id: 'w-selected', nodes: wayNodes, tags: { highway: 'path' } };
  const others = c.otherWays.map((o, k) => ({
    id: `w${k}`,
    tags: o.highway ? { highway: 'residential' } : { barrier: 'fence' },
    locs: o.locs,
  }));
  const graph = {
    parentWays: (node) => {
      const i = nodes.indexOf(node);
      return [way, ...c.nodes[i].others.map((k) => others[k])];
    },
    parentRelations: (node) => (c.nodes[nodes.indexOf(node)].relation ? [{ id: 'r1' }] : []),
    childNodes: (other) => other.locs.map((loc) => ({ loc })),
  };
  return { graph, selection: { way, start: c.start, end: c.end, nodes } };
}

function records(result) {
  return result.map((r) => ({ loc: r.loc, nodeIndex: r.nodeIndex }));
}

function run(fn) {
  try {
    return fn();
  } catch (error) {
    return Promise.reject(error);
  }
}

const output = [];
for (const c of input.cases) {
  const tiles = c.tiles.map(decodeTile);
  const zoom = input.zoom;
  // tiles.js: pad the line's bounding box by the widest search radius plus the contrast neighbourhood
  const padding =
    Math.ceil(
      (SLIDE_DEFAULTS.maxSearchRadiusMeters + SLIDE_DEFAULTS.contrastRadiusMeters) /
        metersPerPixel(c.locations[0][1], zoom)
    ) + 3;
  const bounds = lineBounds(c.locations, zoom, padding);
  const raster = createHeatmapRaster(tiles, zoom, bounds);
  const { graph, selection } = buildGraph(c);
  const fixed = [...new Set([0, c.locations.length - 1, ...fixedIndices(graph, selection)])];
  const otherWays = c.otherWays.filter((o) => o.highway).map((o) => o.locs);
  const options = { fixedIndices: fixed, otherWays, yield: async () => {} };
  const entry = {
    name: c.name,
    bounds,
    fixedIndices: fixed,
    raster: {
      width: raster.width,
      height: raster.height,
      x: raster.x,
      y: raster.y,
      covered: raster.coverage.reduce((a, b) => a + b, 0),
      densitySha256: crypto
        .createHash('sha256')
        .update(Buffer.from(raster.density.buffer))
        .digest('hex'),
    },
  };
  try {
    const once = await run(() => slideLine(c.locations, raster, options));
    entry.once = { records: records(once), skipped: once.skipped };
  } catch (error) {
    entry.once = { error: error.message, noSignal: error.code === 'SLIDE_NO_SIGNAL' };
  }
  try {
    const twice = await run(() => slideLineTwice(c.locations, raster, options));
    entry.twice = { records: records(twice), skipped: twice.skipped };
    const cleaned = cleanSlideResult(graph, selection, twice);
    entry.cleaned = { records: records(cleaned), alongOther: cleaned.alongOther };
  } catch (error) {
    entry.twice = { error: error.message, noSignal: error.code === 'SLIDE_NO_SIGNAL' };
  }
  output.push(entry);
  const summary = entry.twice.error
    ? `error: ${entry.twice.error}`
    : `${entry.twice.records.length} points, ${entry.twice.skipped} skipped, cleaned ${entry.cleaned.records.length}`;
  console.log(`${c.name}: ${summary}`);
}
fs.writeFileSync(
  outputPath,
  zlib.gzipSync(JSON.stringify({ cases: output }), { level: 9 })
);
