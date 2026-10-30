#!/usr/bin/env python3
"""Builds Marietta City Schools' elementary zones from the City of Marietta's public parcel layer.

Marietta City Schools is its own district (not Cobb's). The city publishes no zone polygons any more (the 2019 server
behind its "Elementary School Districts" web map is gone; the ArcGIS Online item https://www.arcgis.com/home/item.html?id=8d29451d94584181bbf03515140d9bf6
is only a layer file for coloring parcels), but every parcel in its public Parcels layer carries the elementary school it
belongs to (field SCHOOL). So the zones are made by grouping parcels by that code:

  1. Fetch all parcels (about 21,500; the layer was edited Sept 2026), keeping geometry and SCHOOL.
  2. Name each code from the school building that sits on a parcel with that code (from Cobb's school-locations layer,
     which also lists Marietta's schools). All seven Marietta elementary schools must map to exactly one code.
  3. Join each code's parcels into one shape, closing the small gaps between them (streets, driveways), and give the
     streets between two zones to one side, so neighboring zones meet instead of leaving a thin gap.

Checks (printed when run): each school's building lies in the zone of its own name; how the result compares with the
Marietta-district-shaped hole in Cobb's 2023-24 PDF (build_cobb_zones.py), a fully independent source.

Limits: the layer's own license says users must verify anything derived from it. Parcels cover the city only, so land
that isn't a parcel (some roads, rail, the air base) is left unzoned beyond the 12 m street closing.

Used by build_school_zones.py. Run directly for the report:  .venv/bin/python data/build_marietta_zones.py
Needs: pip install shapely
"""
import collections
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from shapely.geometry import shape
from shapely.ops import unary_union
from shapely.strtree import STRtree

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "source" / "marietta-parcels.json"
PARCELS = "https://services2.arcgis.com/OKqf9cl3ItPM5Rid/arcgis/rest/services/Parcels_Public_View/FeatureServer/138"
SCHOOLS = "https://services.arcgis.com/HYLRafMc4Ux6DA8c/arcgis/rest/services/Public_Schools_in_Cobb_County/FeatureServer/0"
KX, KY = 92_400.0, 110_540.0  # meters per degree of longitude / latitude near Atlanta
CLOSE_M = 12  # gaps up to twice this (streets, driveways) between parcels of one zone are closed


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=180) as r:
        return json.load(r)


def fetch_parcels(refresh=False):
    if CACHE.exists() and not refresh:
        return json.load(open(CACHE))
    feats, offset = [], 0
    while True:
        q = urllib.parse.urlencode({"where": "1=1", "outFields": "SCHOOL", "outSR": 4326, "geometryPrecision": 6,
                                    "f": "geojson", "resultOffset": offset, "resultRecordCount": 1000})
        page = get_json(f"{PARCELS}/query?{q}")
        feats += page.get("features", [])
        if len(page.get("features", [])) < 1000 and not page.get("exceededTransferLimit"):
            break
        offset += len(page["features"])
    CACHE.parent.mkdir(exist_ok=True)
    json.dump(feats, open(CACHE, "w"))
    return feats


def school_names(parcels):
    """{code: school name}, from the building of each Marietta elementary school. Raises unless every school maps to one code."""
    q = urllib.parse.urlencode({"where": "USER_System='Marietta' AND USER_Type='Elementary'", "outFields": "USER_Name",
                                "outSR": 4326, "f": "geojson"})
    geoms = [(shape(f["geometry"]).buffer(0), (f["properties"].get("SCHOOL") or "").strip()) for f in parcels if f.get("geometry")]
    tree = STRtree([g for g, _ in geoms])
    names = {}
    for f in get_json(f"{SCHOOLS}/query?{q}")["features"]:
        name = f["properties"]["USER_Name"]
        if "Elementary School" not in name:
            continue  # e.g. "Marietta Center for Advanced Academics", which has no zone of its own
        pt = shape(f["geometry"])
        codes = {geoms[i][1] for i in tree.query(pt) if geoms[i][0].contains(pt)} - {""}
        if len(codes) != 1:
            raise SystemExit(f"{name}: building is on parcels with codes {codes}; can't name its zone")
        names[codes.pop()] = name.replace(" Elementary School", "")
    return names


def marietta_zones():
    """([(name, polygon in lon/lat)], report). Zones tile the parcel area with no overlaps."""
    parcels = fetch_parcels()
    names = school_names(parcels)
    by_code = collections.defaultdict(list)
    for f in parcels:
        code = (f["properties"].get("SCHOOL") or "").strip()
        if code and f.get("geometry"):
            g = shape(f["geometry"]).buffer(0)
            if not g.is_empty:
                by_code[code].append(g)
    unknown = set(by_code) - set(names)
    if unknown:
        raise SystemExit(f"parcels use school codes with no school building on them: {sorted(unknown)}")
    d = CLOSE_M / KX
    solid = {c: unary_union(gs) for c, gs in by_code.items()}          # exactly the parcels
    closed = {c: g.buffer(d).buffer(-d) for c, g in solid.items()}      # small gaps closed
    # Each zone keeps its own parcels; streets between two zones go to whichever is handled first.
    claimed, out = None, []
    for code in sorted(closed, key=lambda c: -closed[c].area):
        others = unary_union([solid[c] for c in solid if c != code])
        mine = closed[code].difference(others)
        if claimed is not None:
            mine = mine.difference(claimed)
        claimed = mine if claimed is None else claimed.union(mine)
        out.append((names[code] + " ES", mine.buffer(0)))
    report = {"parcels": len(parcels), "codes": {c: names[c] for c in sorted(names)}, "counts": {c: len(gs) for c, gs in by_code.items()}}
    return out, report


def main():
    zones, report = marietta_zones()
    print(f"{report['parcels']:,} parcels; codes -> schools: {report['codes']}")
    total = 0
    for name, g in sorted(zones):
        km2 = g.area * KX * KY / 1e6
        total += km2
        print(f"  {name:24s} {km2:5.1f} km2")
    print(f"  total {total:.1f} km2")
    # Independent comparison with the Marietta-shaped hole in Cobb's 2023-24 PDF.
    sys.path.insert(0, str(ROOT))
    import build_cobb_zones
    _, cobb_report = build_cobb_zones.cobb_zones()
    hole = cobb_report["marietta_shape"]
    union = unary_union([g for _, g in zones])
    inter = union.intersection(hole).area
    print(f"vs the Marietta-shaped hole in Cobb's PDF: overlap {inter / union.union(hole).area:.3f} (1.0 = identical); "
          f"zones outside the hole {union.difference(hole).area * KX * KY / 1e6:.1f} km2, hole outside the zones {hole.difference(union).area * KX * KY / 1e6:.1f} km2")
    # Every school's building should sit inside the zone with its own name.
    q = urllib.parse.urlencode({"where": "USER_System='Marietta' AND USER_Type='Elementary'", "outFields": "USER_Name", "outSR": 4326, "f": "geojson"})
    bad = 0
    for f in get_json(f"{SCHOOLS}/query?{q}")["features"]:
        name = f["properties"]["USER_Name"].replace(" Elementary School", "") + " ES"
        pt = shape(f["geometry"])
        z = dict(zones).get(name)
        if z is not None and not z.contains(pt):
            bad += 1
            print(f"  building of {name} is not inside its zone")
    print(f"school buildings outside their own zone: {bad}")


if __name__ == "__main__":
    main()
