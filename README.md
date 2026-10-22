# StravaTracer (JOSM plugin)

Click on a trace of the Strava heatmap and the plugin creates the missing way, already connected to the existing ones.

Based on the idea and the algorithm of [osm-strava](https://github.com/archie83/osm-strava) (GPL-3.0).

## Installation

In JOSM, open *Preferences → Plugins*, search for **stravatracer** and enable it.
The plugin can also be downloaded from the [releases](https://github.com/erickdeoliveiraleal/josm-strava-tracer/releases).

## How it works

1. Select the **Strava trace** mode in the left toolbar.
2. Click on a hot trace of the heatmap.
3. The plugin:
   - downloads the tiles around the click **through the Strava imagery layer opened in JOSM**, reusing its URL, cookies and HTTP headers;
   - converts the colours of the "hot" palette back to intensities (0–255); grey level tiles are read directly;
   - masks a band around the ways already in the data layer (`highway`, `railway`, `leisure=track`, `route=ferry`, `aeroway=runway|taxiway`);
   - starting from the hottest pixel near the click, takes the connected trace with a threshold **relative to the popularity of the clicked trace** (rarely and heavily used traces work with the same settings), jumping over small gaps;
   - **follows the trace** at its free ends: looks for the continuation in a cone ahead, up to 12 px, to cross the broken parts of rarely used traces;
   - thins it down to a one pixel line (Zhang-Suen), splits it into lines, removes the short branches (GPS noise) and **joins the branches continuing each other** into a single way; simplifies it (Douglas-Peucker);
   - creates **only the clicked trace**: the traces branching from it can be clicked afterwards, one by one, and get connected to the first one;
   - if the trace reaches the border of the downloaded tiles, **downloads more tiles** and traces again (up to 7×7 tiles);
   - creates the ways with the configured tags, sharing the node at the junctions;
   - connects each free end to the nearest existing way: reuses a node closer than 3 m, or inserts a new node in the way.
4. Everything is a single command: **Ctrl+Z undoes it**. The created ways are selected, so that you can review them and complete their tags.

Always review before uploading: the heatmap shows where people go, not whether there is an actual way.

## Requirements

- JOSM 19613 or newer.
- A visible TMS imagery layer whose name or URL contains `strava`, with a valid access (cookies). URLs containing `/hot/` use the "hot" palette, unless the received tile is a grey level image (new Strava tiles). The other ones are read as grey levels.

## Preferences

Edit them in *Preferences → Advanced*:

| Key | Default | Meaning |
|---|---|---|
| `stravatracer.threshold` | `10` | Absolute minimum intensity (0–255) of a trace pixel |
| `stravatracer.relative` | `0.2` | Minimum intensity relative to the hottest pixel near the click (0–1). Increase it if the way leaks into the surrounding activity; decrease it if the way stops before the end |
| `stravatracer.buffer` | `15` | Band (m) masked around the existing ways |
| `stravatracer.snap` | `40` | Maximum distance (m) to connect a free end to an existing way |
| `stravatracer.zoom` | `16` | Maximum zoom level of the tiles (limited by the maximum zoom of the layer) |
| `stravatracer.radius` | `1` | Tiles initially downloaded on each side of the clicked tile (1 = 3×3) |
| `stravatracer.maxradius` | `3` | Maximum number of tiles on each side when the trace reaches the border (3 = 7×7) |
| `stravatracer.branches` | `false` | Also create the traces branching from the clicked one |
| `stravatracer.simplify` | `1.0` | Simplification tolerance (pixels) |
| `stravatracer.tags` | `highway=path` | Tags of the new ways (`k=v;k=v`) |
| `stravatracer.debug` | `false` | Write the diagnostics after each click, not only when no trace is found |

When no trace is found, the plugin writes to `<JOSM user data directory>/stravatracer-debug/` the tiles (`tiles.png`), the intensities (`intensity.png`: mask in blue, pixels above the threshold in red) and an `info.txt` file with the HTTP responses (without cookies nor query string) and the most frequent colours around the click.

## Build

```bash
mvn package
```

The plugin is built as `target/stravatracer.jar`. To test it, copy this file to `%APPDATA%\JOSM\plugins\` (Windows) or `~/.local/share/JOSM/plugins/` (Linux), restart JOSM and enable **stravatracer** in *Preferences → Plugins*.

## Release

Push a tag `vX.Y.Z`: the GitHub workflow builds the plugin with `Plugin-Version: X.Y.Z` and attaches `stravatracer.jar` to the release. The JOSM plugin list uses `releases/latest/download/stravatracer.jar`, so users get the new version automatically.

## Known limitations

- A trace longer than the maximum window is cut at the border (increase `stravatracer.maxradius`, or click again near the end).
- Parts running parallel to an existing way, closer than `buffer` meters, are not detected.
- Only the active data layer is used for the mask and the connections.
