#!/usr/bin/env python3
"""Generate the test cases comparing the Java port of Slide with the original JavaScript.

Writes src/test/resources/slide/cases.json.gz: heatmap tiles (8-bit heat levels, like the
indexed/grey Strava tiles decoded by png.js) and lines to slide, with the OSM context of
their vertices (tags, relations, connected ways).

    python generate.py [directory of real z15 heat level tiles named 15_<x>_<y>.png]

Synthetic cases are always generated; real cases only when a tile directory is given.
Do not commit cases made from real Strava tiles: the heatmap tiles are Strava's data.
Requires Pillow, numpy and scikit-image (for the real cases).
"""
import base64
import gzip
import json
import math
import os
import random
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ZOOM = 15
TILE = 512
SIZE = TILE * 2 ** ZOOM
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'resources', 'slide', 'cases.json.gz')

# Synthetic area (tiles 12704..12706 x 17697..17699, in Bahia, Brazil)
X0, Y0 = 12704 * TILE, 17697 * TILE


def unproject(p):
    return [p[0] / SIZE * 360 - 180,
            math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * p[1] / SIZE))))]


def project(loc):
    lat = math.radians(loc[1])
    return [(loc[0] + 180) / 360 * SIZE, (1 - math.asinh(math.tan(lat)) / math.pi) / 2 * SIZE]


def meters_per_pixel(lat):
    return 40075016.68557849 * math.cos(math.radians(lat)) / SIZE


def encode(data):
    return base64.b64encode(gzip.compress(bytes(data), 9, mtime=0)).decode()


def render_tiles(trails, tiles, blur=1.2, noise=0, seed=0, holes=(), parents=()):
    """trails: list of (global pixel polyline, width, level 0-255)."""
    rng = random.Random(seed)
    result = []
    for tx, ty in tiles:
        if (tx, ty) in holes:
            continue
        img = Image.new('L', (TILE, TILE), 0)
        d = ImageDraw.Draw(img)
        for line, width, level in trails:
            pts = [(x - tx * TILE, y - ty * TILE) for x, y in line]
            d.line(pts, fill=level, width=width, joint='curve')
        if blur:
            img = img.filter(ImageFilter.GaussianBlur(blur))
        a = np.array(img, dtype=np.int32)
        for _ in range(noise):
            x, y = rng.randrange(TILE), rng.randrange(TILE)
            a[y, x] = max(a[y, x], rng.randrange(3, 30))
        result.append({'z': ZOOM, 'x': tx, 'y': ty, 'width': TILE, 'height': TILE,
                       'data': encode(np.clip(a, 0, 255).astype(np.uint8).tobytes())})
    for tx, ty in parents:
        # Parent tile (zoom 14) covering a missing child, at half the resolution
        img = Image.new('L', (TILE, TILE), 0)
        d = ImageDraw.Draw(img)
        for line, width, level in trails:
            pts = [((x / 2) - tx * TILE, (y / 2) - ty * TILE) for x, y in line]
            d.line(pts, fill=level, width=max(1, width // 2), joint='curve')
        result.append({'z': ZOOM - 1, 'x': tx, 'y': ty, 'width': TILE, 'height': TILE,
                       'data': encode(np.array(img, dtype=np.uint8).tobytes())})
    return result


def curve(points, n=60):
    """Catmull-Rom spline through global pixel points."""
    out = []
    pts = [points[0]] + points + [points[-1]]
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(n):
            t = k / n
            out.append(tuple(0.5 * ((2 * p1[j]) + (-p0[j] + p2[j]) * t
                                    + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t * t
                                    + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t ** 3) for j in range(2)))
    out.append(points[-1])
    return out


def g(x, y):
    """Local synthetic coordinates to global pixels."""
    return (X0 + x, Y0 + y)


def rough(line, every, offset, seed, keep_ends=True):
    """A rough drawing of a polyline: a vertex every `every` pixels, moved by up to `offset` pixels."""
    rng = random.Random(seed)
    out = []
    along = 0
    out.append(line[0])
    for a, b in zip(line, line[1:]):
        along += math.dist(a, b)
        if along >= every:
            along = 0
            out.append((b[0] + rng.uniform(-offset, offset), b[1] + rng.uniform(-offset, offset)))
    if out[-1] != line[-1]:
        out.append(line[-1])
    if not keep_ends:
        out[0] = (out[0][0] + rng.uniform(-offset, offset), out[0][1] + rng.uniform(-offset, offset))
    return out


def node(tagged=False, relation=False, others=()):
    return {'tagged': tagged, 'relation': relation, 'others': list(others)}


def case(name, tiles, line_px, nodes=None, other_ways=(), start=0, way_count=None):
    locations = [unproject(p) for p in line_px]
    nodes = nodes or [node() for _ in locations]
    assert len(nodes) == len(locations)
    return {
        'name': name,
        'tiles': tiles,
        'locations': locations,
        'nodes': nodes,
        'otherWays': [{'highway': hw, 'locs': locs} for hw, locs in other_ways],
        'start': start,
        'end': start + len(locations) - 1,
        'wayNodeCount': way_count or len(locations),
    }


def synthetic_cases():
    area = [(x, y) for x in range(12704, 12707) for y in range(17697, 17700)]
    cases = []

    # 1. A curvy trail drawn roughly, a few metres off
    trail = curve([g(100, 300), g(400, 380), g(700, 300), g(950, 450), g(1200, 420), g(1400, 600)])
    tiles = render_tiles([(trail, 4, 120)], area, noise=3000, seed=1)
    cases.append(case('curvy-rough', tiles, rough(trail, 30, 4, 11)))
    cases.append(case('curvy-sparse', tiles, rough(trail, 120, 3, 12)))

    # 2. Switchbacks (zigzag) drawn with few vertices
    zig = curve([g(500, 300), g(700, 360), g(520, 420), g(720, 480), g(540, 540), g(740, 600)], 40)
    tiles = render_tiles([(zig, 3, 90)], area, noise=1500, seed=2)
    cases.append(case('switchbacks', tiles, rough(zig, 18, 2, 21)))

    # 3. A faint trail next to a brighter parallel one (about 15 m away)
    faint = curve([g(200, 700), g(600, 720), g(1000, 690), g(1300, 730)])
    bright = [(x, y + 7) for x, y in faint]
    tiles = render_tiles([(faint, 3, 40), (bright, 5, 220)], area, noise=2000, seed=3)
    cases.append(case('faint-next-to-bright', tiles, rough(faint, 40, 2, 31)))

    # 3b. The same, but the bright trail is a mapped road (its heat is masked, see maskOtherWays)
    cases.append(case('faint-next-to-mapped-road', tiles, rough(faint, 40, 2, 32), None,
                      [(True, [unproject(p) for p in bright[::10] + [bright[-1]]])]))
    # 3c. A rough line drawn between the two, closer to the bright trail
    middle = [(x, y + 5) for x, y in faint]
    cases.append(case('between-faint-and-bright', tiles, rough(middle, 40, 2, 33)))

    # 4. A trail meeting a mapped road: junction node shared with the road (other way)
    road = [g(150, 900), g(1400, 1000)]
    path = curve([g(700, 300), g(760, 500), g(720, 700), g(790, 944)])
    tiles = render_tiles([(path, 3, 100), (road, 8, 250)], area, noise=2000, seed=4)
    line = rough(path, 35, 4, 41)
    junction = path[-1]
    line[-1] = junction
    road_line = [road[0], junction, road[1]]
    nodes = [node() for _ in line]
    nodes[-1] = node(others=[0])
    cases.append(case('junction-with-road', tiles, line, nodes,
                      [(True, [unproject(p) for p in road_line])]))
    # 4b. The same junction, but the connected way is a fence (not a highway: no heat of its own)
    cases.append(case('junction-with-fence', tiles, line, nodes,
                      [(False, [unproject(p) for p in road_line])]))
    # 4c. Junction in the middle of the line, with a road crossing it
    cross = [g(700, 200), path[len(path) // 2], g(900, 260)]
    line_c = rough(path, 35, 4, 42)
    mid = len(line_c) // 2
    line_c[mid] = path[len(path) // 2]
    nodes_c = [node() for _ in line_c]
    nodes_c[mid] = node(others=[0])
    tiles_c = render_tiles([(path, 3, 100), (cross, 6, 200)], area, noise=2000, seed=44)
    cases.append(case('junction-in-the-middle', tiles_c, line_c, nodes_c,
                      [(True, [unproject(p) for p in cross])]))

    # 5. Tagged and relation member nodes, partial selection of a longer way
    nodes = [node() for _ in rough(trail, 30, 4, 51)]
    line = rough(trail, 30, 4, 51)
    nodes[3] = node(tagged=True)
    nodes[6] = node(relation=True)
    tiles = render_tiles([(trail, 4, 120)], area, noise=3000, seed=1)
    cases.append(case('partial-with-protected', tiles, line, nodes, start=2, way_count=len(line) + 5))

    # 6. A dashed faint trail (gaps), drawn roughly
    dashed = curve([g(200, 1100), g(600, 1150), g(1000, 1080), g(1400, 1200)])
    pieces = [dashed[i:i + 12] for i in range(0, len(dashed), 16)]
    tiles = render_tiles([(p, 3, 60) for p in pieces if len(p) > 1], area, noise=2500, seed=6)
    cases.append(case('dashed-faint', tiles, rough(dashed, 45, 3, 61)))

    # 7. A line partly without heat (the trail stops): sections left as drawn
    half = curve([g(100, 1300), g(500, 1350), g(800, 1300)])
    tiles = render_tiles([(half, 4, 130)], area, noise=1000, seed=7)
    extension = [g(900, 1320), g(1100, 1360), g(1300, 1330)]
    cases.append(case('heat-stops', tiles, rough(half, 40, 3, 71) + extension))

    # 8. A closed loop
    loop = curve([g(300, 400), g(600, 250), g(900, 400), g(750, 750), g(400, 700), g(300, 400)])
    tiles = render_tiles([(loop, 4, 110)], area, noise=2000, seed=8)
    line = rough(loop, 40, 3, 81)
    line[-1] = line[0]
    cases.append(case('closed-loop', tiles, line))

    # 9. Missing tile with a zoom 14 parent, and a coverage hole
    trail9 = curve([g(100, 200), g(700, 260), g(1300, 230)])
    tiles = render_tiles([(trail9, 4, 140)], area, noise=1000, seed=9,
                         holes={(12705, 17697)}, parents={(12705 // 2, 17697 // 2)})
    cases.append(case('parent-tile-fallback', tiles, rough(trail9, 40, 3, 91)))
    tiles = render_tiles([(trail9, 4, 140)], area, noise=1000, seed=9, holes={(12705, 17697)})
    cases.append(case('coverage-hole', tiles, rough(trail9, 40, 3, 92)))

    # 10. No heat at all: Slide must fail with "no signal"
    tiles = render_tiles([], area, noise=200, seed=10)
    cases.append(case('no-heat', tiles, rough(trail9, 60, 3, 93)))
    return cases


def skeleton_paths(levels, threshold):
    from skimage.morphology import skeletonize
    skel = skeletonize(levels >= threshold)
    pixels = set(zip(*np.nonzero(skel)))

    def neighbours(p):
        return [(p[0] + dy, p[1] + dx) for dy in (-1, 0, 1) for dx in (-1, 0, 1)
                if (dy or dx) and (p[0] + dy, p[1] + dx) in pixels]

    ends = [p for p in sorted(pixels) if len(neighbours(p)) == 1]
    seen = set()
    paths = []
    for start in ends:
        if start in seen:
            continue
        path = [start]
        seen.add(start)
        cur = start
        while True:
            nxt = [n for n in neighbours(cur) if n not in seen]
            if len(nxt) != 1:
                break
            cur = nxt[0]
            seen.add(cur)
            path.append(cur)
        paths.append(path)
    paths.sort(key=len, reverse=True)
    return paths


def real_cases(directory):
    files = [f for f in os.listdir(directory) if f.startswith('15_') and f.endswith('.png')]
    coords = sorted(tuple(int(v) for v in f[:-4].split('_')[1:]) for f in files)
    xs = [c[0] for c in coords]
    ys = [c[1] for c in coords]
    x0, y0 = min(xs), min(ys)
    w, h = max(xs) - x0 + 1, max(ys) - y0 + 1
    mosaic = np.zeros((h * TILE, w * TILE), dtype=np.uint8)
    tiles = {}
    for (x, y) in coords:
        img = Image.open(os.path.join(directory, f'15_{x}_{y}.png')).convert('L')
        a = np.array(img)
        # Some cached tiles are 256 pixels: kept as they are in the case (Slide scales them)
        mosaic[(y - y0) * TILE:(y - y0 + 1) * TILE, (x - x0) * TILE:(x - x0 + 1) * TILE] = \
            np.array(img.resize((TILE, TILE), Image.NEAREST))
        tiles[(x, y)] = {'z': ZOOM, 'x': x, 'y': y, 'width': img.width, 'height': img.height,
                         'data': encode(a.tobytes())}
    cases = []
    for n, path in enumerate(skeleton_paths(mosaic, 12)[:10]):
        if len(path) < 120:
            continue
        sub = path[10:min(len(path) - 10, 10 + 900)]
        line = [(x0 * TILE + c + 0.5, y0 * TILE + r + 0.5) for r, c in sub]
        drawn = rough(line, 20 + 5 * (n % 3), 2 + n % 4, 100 + n)
        # Only the tiles around the line (the loader downloads the same)
        lat = unproject(drawn[0])[1]
        pad = math.ceil((80 + 12) / meters_per_pixel(lat)) + 3
        minx = int((min(p[0] for p in drawn) - pad) // TILE)
        maxx = int((max(p[0] for p in drawn) + pad) // TILE)
        miny = int((min(p[1] for p in drawn) - pad) // TILE)
        maxy = int((max(p[1] for p in drawn) + pad) // TILE)
        used = [tiles[(x, y)] for x in range(minx, maxx + 1) for y in range(miny, maxy + 1) if (x, y) in tiles]
        cases.append(case(f'real-{n}', used, drawn))
    return cases


def main():
    cases = synthetic_cases()
    if len(sys.argv) > 1:
        cases += real_cases(sys.argv[1])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, 'wt', encoding='utf-8', compresslevel=9) as f:
        json.dump({'zoom': ZOOM, 'cases': cases}, f)
    print(len(cases), 'cases written to', os.path.normpath(OUT), os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
