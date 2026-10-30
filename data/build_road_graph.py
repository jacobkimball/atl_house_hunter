#!/usr/bin/env python3
"""Builds data/assets/road_graph.bin, the street network behind snap-to-street.

Input : data/source/georgia-latest.osm.pbf  (download.geofabrik.de; not committed, ~350 MB)
Output: data/assets/road_graph.bin

A street is cut into "edges" at every junction (a node shared by two ways, or the end of a way), so the app
can follow a street through curves and hop between streets only at real intersections. Only streets people
live on are kept: no footpaths, sidewalks, cycleways, parking-lot aisles or driveways.

Run:  .venv/bin/python data/build_road_graph.py

File layout (all little-endian; see RoadGraph.kt, the reader):
    magic "HHRG", u32 version, u32 numNodes, u32 numEdges, u32 numVerts, u32 numNames
    i32[numEdges]      from-node index
    i32[numEdges]      to-node index
    i32[numEdges+1]    first vertex of each edge (the last entry is numVerts)
    u8[numEdges]       road class (0 freeway/trunk, 1 primary, 2 secondary, 3 tertiary, 4 local), padded to 4 bytes
    i32[numEdges]      name index (-1 = unnamed)
    i32[numVerts]      latitude  in millionths of a degree
    i32[numVerts]      longitude in millionths of a degree
    names: numNames x (u16 byte length, UTF-8 bytes)
"""
import math
import struct
import sys
import time
from collections import Counter
from pathlib import Path

import osmium

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source" / "georgia-latest.osm.pbf"
OUT = ROOT / "assets" / "road_graph.bin"

# Keep in sync with build_data.sh (lon_min, lat_min, lon_max, lat_max).
BBOX = (-84.60, 33.60, -84.20, 34.02)

ROAD_CLASS = {
    "motorway": 0, "motorway_link": 0, "trunk": 0, "trunk_link": 0,
    "primary": 1, "primary_link": 1,
    "secondary": 2, "secondary_link": 2,
    "tertiary": 3, "tertiary_link": 3,
    "unclassified": 4, "residential": 4, "living_street": 4,
}


def read_ways():
    """Every kept street way that touches the coverage box: (node ids, [(lat, lon)], name, class)."""
    lon0, lat0, lon1, lat1 = BBOX
    ways = []
    seen = 0
    # Locations must be attached before the tag filter runs, so add them first.
    processor = osmium.FileProcessor(str(SOURCE)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for obj in processor:
        if not obj.is_way():
            continue
        seen += 1
        tags = obj.tags
        cls = ROAD_CLASS.get(tags.get("highway"))
        if cls is None or tags.get("area") == "yes":
            continue
        ids, coords, inside = [], [], False
        for n in obj.nodes:
            loc = n.location
            if not loc.valid():
                continue
            ids.append(n.ref)
            coords.append((loc.lat, loc.lon))
            if lon0 <= loc.lon <= lon1 and lat0 <= loc.lat <= lat1:
                inside = True
        if inside and len(ids) >= 2:
            ways.append((ids, coords, tags.get("name") or tags.get("ref") or "", cls))
    print(f"read {seen:,} highway ways, kept {len(ways):,} streets in the coverage box")
    return ways


def meters(a, b):
    dy = (b[0] - a[0]) * 110540.0
    dx = (b[1] - a[1]) * 111320.0 * math.cos(math.radians((a[0] + b[0]) / 2))
    return math.hypot(dx, dy)


def build(ways):
    uses = Counter()
    for ids, _, _, _ in ways:
        uses.update(ids)

    node_index = {}          # OSM node id -> compact node index (junctions and dead ends only)

    def node(nid):
        if nid not in node_index:
            node_index[nid] = len(node_index)
        return node_index[nid]

    names = {}
    edges = []               # (from, to, class, name index, [(lat, lon), ...])
    for ids, coords, name, cls in ways:
        name_idx = -1
        if name:
            name_idx = names.setdefault(name, len(names))
        start = 0
        for i in range(1, len(ids)):
            last = i == len(ids) - 1
            if uses[ids[i]] >= 2 or last:
                pts = coords[start:i + 1]
                length = sum(meters(pts[k], pts[k + 1]) for k in range(len(pts) - 1))
                if length >= 0.05:
                    edges.append((node(ids[start]), node(ids[i]), cls, name_idx, pts))
                start = i
    return edges, len(node_index), list(names)


def write(edges, node_count, names):
    verts = sum(len(e[4]) for e in edges)
    out = bytearray()
    out += b"HHRG" + struct.pack("<5I", 1, node_count, len(edges), verts, len(names))
    out += struct.pack(f"<{len(edges)}i", *(e[0] for e in edges))
    out += struct.pack(f"<{len(edges)}i", *(e[1] for e in edges))
    start, offsets = 0, []
    for e in edges:
        offsets.append(start)
        start += len(e[4])
    offsets.append(start)
    out += struct.pack(f"<{len(offsets)}i", *offsets)
    classes = bytes(e[2] for e in edges)
    out += classes + b"\0" * (-len(classes) % 4)
    out += struct.pack(f"<{len(edges)}i", *(e[3] for e in edges))
    out += struct.pack(f"<{verts}i", *(round(p[0] * 1e6) for e in edges for p in e[4]))
    out += struct.pack(f"<{verts}i", *(round(p[1] * 1e6) for e in edges for p in e[4]))
    for name in names:
        raw = name.encode("utf-8")[:65535]
        out += struct.pack("<H", len(raw)) + raw
    OUT.write_bytes(out)
    return len(out), verts


def report(edges, node_count, size, verts):
    total_km = sum(meters(e[4][k], e[4][k + 1]) for e in edges for k in range(len(e[4]) - 1)) / 1000
    parent = list(range(node_count))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for e in edges:
        parent[find(e[0])] = find(e[1])
    sizes = Counter(find(i) for i in range(node_count))
    biggest = max(sizes.values())
    degree = Counter()
    for e in edges:
        degree[e[0]] += 1
        degree[e[1]] += 1
    classes = Counter(e[2] for e in edges)
    print(f"edges {len(edges):,} | junction/end nodes {node_count:,} | vertices {verts:,} | {total_km:,.0f} km of street")
    print(f"connected pieces {len(sizes):,}; largest holds {biggest:,} nodes ({100 * biggest / node_count:.1f}%)")
    print(f"dead ends (degree 1): {sum(1 for d in degree.values() if d == 1):,}; "
          f"real junctions (degree>=3): {sum(1 for d in degree.values() if d >= 3):,}")
    print("edges by class (0 freeway .. 4 local):", dict(sorted(classes.items())))
    print(f"wrote {OUT.relative_to(ROOT.parent)}: {size / 1e6:.1f} MB")


if __name__ == "__main__":
    if not SOURCE.exists():
        sys.exit(f"missing {SOURCE}: download it from https://download.geofabrik.de/north-america/us/georgia-latest.osm.pbf")
    t0 = time.time()
    ways = read_ways()
    edges, node_count, names = build(ways)
    size, verts = write(edges, node_count, names)
    report(edges, node_count, size, verts)
    print(f"done in {time.time() - t0:.0f}s")
