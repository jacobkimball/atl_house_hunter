#!/usr/bin/env python3
"""Builds data/assets/search_index.json: everything the search box can find, with where to fly the map.

Entries (each a JSON array): [name, type, lat, lon, west, south, east, north, area, length]
  type 0 city / town      1 neighborhood      2 subdivision      3 MARTA station      4 street
  length  street length in meters (0 for everything else); the search ranks longer, more important streets first
  area  a short "where is it" label so two streets with one name can be told apart (e.g. "Ansley Park").

Streets: every named street, one entry per connected stretch of it. "Oak Drive" in two different towns
becomes two entries. Address search isn't possible: OpenStreetMap has no house numbers for most of Atlanta.

Needs the outputs of build_neighborhoods.py, build_rail.py (stations) and the OSM extract (see build_road_graph.py).
Run:  .venv/bin/python data/build_search_index.py
"""
import json
import math
import sys
from pathlib import Path

from shapely.geometry import Point, shape
from shapely.strtree import STRtree

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_neighborhoods as bn  # noqa: E402
import build_road_graph as brg  # noqa: E402

ASSETS = Path(__file__).resolve().parent / "assets"
CITY, NEIGHBORHOOD, SUBDIVISION, STATION, STREET = range(5)


def load(name):
    return json.load(open(ASSETS / name))["features"]


def entry(name, kind, lat, lon, bbox, area, length=0):
    w, s, e, n = bbox
    return [name, kind, round(lat, 5), round(lon, 5), round(w, 5), round(s, 5), round(e, 5), round(n, 5), area, round(length)]


class Areas:
    """Answers "which neighborhood or town is this point in?" for the street labels."""

    def __init__(self, atlanta, civic, points):
        self.polys = [(shape(f["geometry"]), f["properties"]["NAME"] if "NAME" in f["properties"] else f["properties"]["name"])
                      for f in atlanta + civic]
        self.tree = STRtree([p for p, _ in self.polys])
        self.points = points  # (name, lon, lat, kind)
        self.pts = [Point(x, y) for _, x, y, _ in points]
        self.pt_tree = STRtree(self.pts)

    def label(self, lat, lon):
        here = Point(lon, lat)
        for i in self.tree.query(here, predicate="within"):
            return self.polys[i][1]
        best, best_d = "", 1e9
        for kind, radius in (("neighbourhood", 1500), ("locality", 8000)):
            for i in self.pt_tree.query(here.buffer(radius / 100_000)):
                name, x, y, k = self.points[i]
                if k not in (kind, "macrohood" if kind == "neighbourhood" else kind):
                    continue
                d = math.hypot((x - lon) * 92_400, (y - lat) * 110_540)
                if d < min(best_d, radius):
                    best, best_d = name, d
            if best:
                return best
        return ""


def street_entries(areas):
    ways = brg.read_ways()
    edges, _, names = brg.build(ways)
    # Group edges of the same street name that touch each other into one stretch.
    parent = list(range(len(edges)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    by_node = {}
    for i, (a, b, _cls, name_idx, _pts) in enumerate(edges):
        if name_idx < 0:
            continue
        for node in (a, b):
            key = (name_idx, node)
            if key in by_node:
                parent[find(i)] = find(by_node[key])
            else:
                by_node[key] = i
    groups = {}
    for i, e in enumerate(edges):
        if e[3] >= 0:
            groups.setdefault(find(i), []).append(i)

    out = []
    for members in groups.values():
        pts = [p for i in members for p in edges[i][4]]
        length = sum(brg.meters(p, q) for i in members for p, q in zip(edges[i][4], edges[i][4][1:]))
        if length < 25:
            continue
        lats = [p[0] for p in pts]
        lons = [p[1] for p in pts]
        mid = pts[len(pts) // 2]
        out.append((entry(names[edges[members[0]][3]], STREET, mid[0], mid[1], (min(lons), min(lats), max(lons), max(lats)),
                          areas.label(mid[0], mid[1]), length), length))
    return out


def main():
    atlanta = load("atlanta_neighborhoods.geojson")
    subs = load("subdivisions.geojson")
    civic = [f for f in subs if f["properties"]["scale"] == "neighborhood"]
    osm = load("osm_neighborhoods.geojson")
    localities = [(n, x, y, "locality") for (n, x, y), _ in bn.osm_points(("locality",)).items()]
    points = [(f["properties"]["name"], f["geometry"]["coordinates"][0], f["geometry"]["coordinates"][1], "neighbourhood") for f in osm] + localities
    areas = Areas(atlanta, civic, points)

    rows = []
    for f in atlanta:
        g = shape(f["geometry"])
        c = g.representative_point()
        rows.append(entry(f["properties"]["NAME"], NEIGHBORHOOD, c.y, c.x, g.bounds, "Atlanta"))
    for f in subs:
        g = shape(f["geometry"])
        c = g.representative_point()
        p = f["properties"]
        rows.append(entry(p["name"], NEIGHBORHOOD if p["scale"] == "neighborhood" else SUBDIVISION, c.y, c.x, g.bounds, p["city"]))
    for f in osm:
        x, y = f["geometry"]["coordinates"]
        rows.append(entry(f["properties"]["name"], NEIGHBORHOOD, y, x, (x, y, x, y), areas.label(y, x)))
    for n, x, y, _ in localities:
        rows.append(entry(n, CITY, y, x, (x, y, x, y), ""))
    for f in load("stations.geojson"):
        x, y = f["geometry"]["coordinates"]
        rows.append(entry(f["properties"]["name"] + " (MARTA)", STATION, y, x, (x, y, x, y), "MARTA station"))
    streets = street_entries(areas)
    rows.extend(e for e, _ in streets)

    (ASSETS / "search_index.json").write_text(json.dumps(rows, separators=(",", ":"), ensure_ascii=False))
    kinds = {}
    for r in rows:
        kinds[r[1]] = kinds.get(r[1], 0) + 1
    print(f"search_index.json: {len(rows):,} entries {dict(sorted(kinds.items()))} "
          f"(0 city, 1 neighborhood, 2 subdivision, 3 station, 4 street); {(ASSETS / 'search_index.json').stat().st_size / 1e6:.1f} MB")
    named = [r for r in rows if r[1] == STREET]
    print("sample street entries:", [f"{r[0]} ({r[8] or '-'})" for r in named[:6]])
    print("'Peachtree Street Northeast' stretches:", [(r[8] or '-') for r in named if r[0] == "Peachtree Street Northeast"])


if __name__ == "__main__":
    main()
