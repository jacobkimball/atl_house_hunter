#!/usr/bin/env python3
"""Builds data/assets/school_zones.geojson: elementary school attendance zones inside the coverage area.

Sources (each district's own published data where it exists):
  DeKalb County School District   Elementary_School_Attendance_Areas   (DCSD's ArcGIS org; edited Sept 2026)
  Fulton County Schools           CombinedAttendanceZones "FY2627"     (www4.fultonschools.org, FCS's own server)
  Atlanta Public Schools          APS_elementary_zones_with_school_data (a third-party compilation, Aug 2026. APS's own
                                  APSElementaryZones layer is the 2006-07 map, so it can't be used.)

  City Schools of Decatur         Traced from the district's official 2025-26 boundary map (a PDF); see build_decatur_zones.py.
                                  Decatur has two zone sets: K-2 lower schools (tier "lower", filled like every other
                                  zone) and 3-5 upper schools (tier "upper", drawn as outlines so they don't hide the rest).

  Cobb County School District     Traced from the district's official 2023-24 elementary zone map (a PDF); see build_cobb_zones.py.
                                  This is the oldest data here.
  Marietta City Schools           Built from the City of Marietta's public parcel layer (each parcel records its elementary
                                  school; edited Sept 2026); see build_marietta_zones.py. Cobb's zones are trimmed to meet it.

Each output feature has: name, district, source (a short note shown to the user), tier ("" for most districts),
c (0-5, a map-colouring index so neighbouring zones get different fills). Geometry is clipped to the coverage box and
lightly simplified.

Run:  .venv/bin/python data/build_school_zones.py
"""
import json
import re
import sys
import urllib.parse
import urllib.request
from itertools import combinations
from pathlib import Path

from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union
from shapely.strtree import STRtree


sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_cobb_zones  # noqa: E402
import build_marietta_zones  # noqa: E402
import build_decatur_zones  # noqa: E402

OUT = Path(__file__).resolve().parent / "assets" / "school_zones.geojson"
BBOX = (-84.60, 33.60, -84.20, 34.02)  # keep in sync with build_data.sh
MARGIN = 0.01  # keep zones that just touch the edge of the map

SOURCES = [
    {
        "district": "DeKalb County School District",
        "short": "DeKalb",
        "url": "https://services3.arcgis.com/3TzhpgpIaE4cOUGc/arcgis/rest/services/Elementary_School_Attendance_Areas/FeatureServer/0",
        "where": "1=1",
        "name": lambda p: p["ES_Name"],
        "note": "DeKalb County School District (official; edited Sep 2026)",
    },
    {
        "district": "Fulton County Schools",
        "short": "Fulton",
        "url": "https://www4.fultonschools.org/arcgisserver/rest/services/AttendanceZones/CombinedAttendanceZones/FeatureServer/22",
        "where": "zonetype LIKE '%Elementary%'",
        "name": lambda p: p["name_1633352979610"],
        "note": "Fulton County Schools (official; school year 2026-27)",
    },
    {
        "district": "Atlanta Public Schools",
        "short": "APS",
        "url": "https://services5.arcgis.com/5RxyIIJ9boPdptdo/arcgis/rest/services/APS_elementary_zones_with_school_data/FeatureServer/0",
        "where": "1=1",
        "name": lambda p: p["Elementary"],
        "note": "Atlanta Public Schools zones from a third-party compilation (Aug 2026). Confirm with APS before relying on it",
    },
]


def fetch(source):
    query = urllib.parse.urlencode({
        "where": source["where"], "outFields": "*", "outSR": 4326, "f": "geojson", "resultRecordCount": 2000,
    })
    req = urllib.request.Request(source["url"] + "/query?" + query, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.load(r)["features"]


SMALL_WORDS = {"and", "of", "the", "at"}


def tidy_name(raw):
    """'Allgood ES' / 'Alpharetta Elementary School' / 'Barack And Michelle Obama' -> 'Allgood ES' etc."""
    name = re.sub(r"\s+", " ", raw or "").strip()
    name = re.sub(r"\s+(Elementary School|Elementary|ES)$", "", name, flags=re.I).strip()
    words = [w.lower() if i and w.lower() in SMALL_WORDS else w for i, w in enumerate(name.split(" "))]
    name = " ".join(words)
    return name if re.search(r"Academy|School|Center", name) else name + " ES"


def main():
    area = box(BBOX[0] - MARGIN, BBOX[1] - MARGIN, BBOX[2] + MARGIN, BBOX[3] + MARGIN)
    kept = []
    for source in SOURCES:
        feats = fetch(source)
        inside = 0
        for f in feats:
            if not f.get("geometry"):
                continue
            geom = shape(f["geometry"])
            if not geom.is_valid:
                geom = geom.buffer(0)
            clipped = geom.intersection(area)
            if clipped.is_empty or clipped.area < 1e-8:
                continue
            clipped = clipped.simplify(0.00003, preserve_topology=True)
            kept.append({
                "geometry": clipped,
                "name": tidy_name(source["name"](f["properties"])),
                "district": source["district"],
                "short": source["short"],
                "note": source["note"],
            })
            inside += 1
        print(f"{source['short']:7s} {len(feats):3d} zones fetched, {inside:3d} touch the map area")

    decatur, fit, _ = build_decatur_zones.decatur_zones()
    note = "City Schools of Decatur, 2025-26 official boundary map (traced from a PDF; edges are accurate to a few meters)"
    for name, tier, geom in decatur:
        clipped = geom.intersection(area)
        if not clipped.is_empty:
            kept.append({"geometry": clipped.simplify(0.00003, preserve_topology=True), "name": name,
                         "district": "City Schools of Decatur", "short": "Decatur", "note": note, "tier": tier})
    print(f"Decatur {len(decatur):3d} zones traced (fit {fit.iou:.3f} against the district outline)")

    cobb, cobb_report = build_cobb_zones.cobb_zones()
    note = "Cobb County School District, 2023-24 official zone map (traced from a PDF; older than the other data, so confirm with Cobb)"
    cobb_kept = 0
    for name, geom in cobb:
        clipped = geom.intersection(area)
        if not clipped.is_empty and clipped.area > 1e-8:
            kept.append({"geometry": clipped.simplify(0.00003, preserve_topology=True), "name": name,
                         "district": "Cobb County School District", "short": "Cobb", "note": note, "tier": ""})
            cobb_kept += 1
    print(f"Cobb    {len(cobb):3d} zones traced, {cobb_kept:3d} touch the map area "
          f"(county outline overlap {cobb_report['county_overlap']:.3f}, road-fit median {cobb_report['road_residual_m']:.1f} m)")

    marietta, _ = build_marietta_zones.marietta_zones()
    marietta_note = ("Marietta City Schools, from the City of Marietta's parcel records (current as of Sep 2026); "
                     "the city says users must verify GIS data, so confirm with the district")
    marietta_union = unary_union([g for _, g in marietta])
    # The parcels are exact to the property line, so where Cobb's traced zones (2023-24 map, ~40 m placement error)
    # overlap them, the parcels win.
    for k in kept:
        if k["short"] == "Cobb":
            k["geometry"] = k["geometry"].difference(marietta_union)
    kept = [k for k in kept if not k["geometry"].is_empty and k["geometry"].area > 1e-8]
    # Slivers between the two sources (streets, the difference in precision) that Cobb's map shows as part of Marietta's
    # area go to the nearest Marietta zone, so the seam has no thin gap.
    hole = cobb_report["marietta_shape"]
    leftover = hole.difference(unary_union([k["geometry"] for k in kept if k["short"] == "Cobb"] + [marietta_union]))
    zone_geoms = dict(marietta)
    for piece in (leftover.geoms if hasattr(leftover, "geoms") else [leftover]):
        if piece.area < 1e-9:
            continue
        near = min(zone_geoms, key=lambda n: zone_geoms[n].distance(piece))
        if zone_geoms[near].distance(piece) < 100 / 92_400:
            zone_geoms[near] = zone_geoms[near].union(piece)
    marietta_kept = 0
    for name, geom in zone_geoms.items():
        clipped = geom.buffer(0).intersection(area)
        if not clipped.is_empty and clipped.area > 1e-8:
            kept.append({"geometry": clipped.simplify(0.00003, preserve_topology=True), "name": name,
                         "district": "Marietta City Schools", "short": "Marietta", "note": marietta_note, "tier": ""})
            marietta_kept += 1
    print(f"Marietta {marietta_kept:2d} zones built from parcels")

    # Overlap check: zones from different districts should not cover the same ground.
    tree = STRtree([k["geometry"] for k in kept])
    worst = []
    for i, k in enumerate(kept):
        for j in tree.query(k["geometry"]):
            if j <= i or kept[j]["short"] == k["short"]:
                continue
            overlap = k["geometry"].intersection(kept[j]["geometry"]).area
            small = min(k["geometry"].area, kept[j]["geometry"].area)
            if overlap / small > 0.02:
                worst.append((overlap / small, k["short"], k["name"], kept[j]["short"], kept[j]["name"]))
    worst.sort(reverse=True)
    print(f"cross-district overlaps above 2% of the smaller zone: {len(worst)}")
    for frac, d1, n1, d2, n2 in worst[:8]:
        print(f"   {frac:5.1%}  {d1}:{n1}  <->  {d2}:{n2}")

    # Map-colouring: greedy, so touching zones get different fill colours.
    filled = [k for k in kept if k.get("tier") != "upper"]  # upper (3-5) zones are outlines only
    for k in kept:
        k.setdefault("tier", "")
        if k["tier"] == "upper":
            k["c"] = 0
    tree = STRtree([k["geometry"] for k in filled])
    order = sorted(range(len(filled)), key=lambda i: -filled[i]["geometry"].area)
    for i in order:
        used = {filled[j].get("c") for j in tree.query(filled[i]["geometry"].buffer(1e-5)) if j != i and filled[j].get("c") is not None}
        filled[i]["c"] = next(c for c in range(50) if c not in used)
    print("colours used:", max(k["c"] for k in filled) + 1)

    features = [{
        "type": "Feature",
        "properties": {"name": k["name"], "district": k["district"], "source": k["note"], "tier": k["tier"], "c": k["c"]},
        "geometry": json.loads(json.dumps(mapping(k["geometry"]), default=lambda v: v), parse_float=lambda s: round(float(s), 6)),
    } for k in kept]
    OUT.write_text(json.dumps({"type": "FeatureCollection", "features": features}, separators=(",", ":")))
    print(f"wrote {OUT.name}: {len(features)} zones, {OUT.stat().st_size / 1e6:.2f} MB")
    print("names:", ", ".join(sorted(k["name"] for k in kept))[:900])


if __name__ == "__main__":
    main()
