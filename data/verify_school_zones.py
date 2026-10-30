#!/usr/bin/env python3
"""Spot-checks school zones in data/assets/school_zones.geojson against independent sources.

For each school named on the command line it reports:
  - the zone's size, and whether the school's own building (from the district's school-locations layer) is inside it
  - Atlanta Public Schools zones: overlap (IoU, 1.0 = identical) with APS's own older/planning layers
      * MidtownESZones2023_24   APS's adopted 2023-24 plan for the Midtown cluster (Lin, Springdale Park, Morningside...)
      * APSElementaryZones      APS's 2006-07 map (stale, but shows what has moved since)
  - DeKalb zones: rebuilds the zone from DeKalb's census-block school assignments and compares it with the polygon,
    and checks the school's state code matches between two DeKalb layers
  - Atlanta neighborhoods (official polygons) and how much of each lies inside the zone, so you can eyeball it
  - which zones touch it, and any uncovered ground within ~150 m of its edge (gaps or slivers along borders)

These checks show that the layer agrees with other official data. They can't show what a district decided after the
latest files were published. Anything printed as low overlap deserves a look at the district's own locator.

Run:  .venv/bin/python data/verify_school_zones.py [School name ...]     (default: Fernbank "Springdale Park" "Garden Hills" Lin)
"""
import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

from shapely.geometry import shape
from shapely.ops import unary_union

ASSETS = Path(__file__).resolve().parent / "assets"
KX, KY = 92_400.0, 110_540.0  # meters per degree of longitude / latitude near Atlanta

APS = "https://services1.arcgis.com/stuCU7Ai47X10WDV/arcgis/rest/services"
DEKALB = "https://services3.arcgis.com/3TzhpgpIaE4cOUGc/arcgis/rest/services"


def get(url, timeout=180):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=timeout) as r:
        return json.load(r)


def features(layer_url, where="1=1", fields="*"):
    query = urllib.parse.urlencode({"where": where, "outFields": fields, "outSR": 4326, "f": "geojson", "resultRecordCount": 1000})
    return get(f"{layer_url}/query?{query}")["features"]


def km2(g):
    return g.area * KX * KY / 1e6


def iou(a, b):
    union = a.union(b).area
    return a.intersection(b).area / union if union else 0.0


def squash(text):
    return "".join(ch for ch in (text or "").lower() if ch.isalnum()).replace("proposed", "").replace("elementary", "")


def matches(zone_name, other_name, ):
    a, b = squash(zone_name).removesuffix("es"), squash(other_name).removesuffix("es")
    return bool(a) and (a in b or b in a)


def main():
    wanted = sys.argv[1:] or ["Fernbank", "Springdale Park", "Garden Hills", "Lin"]
    zones = [f for f in json.load(open(ASSETS / "school_zones.geojson"))["features"]]
    by_name = {f["properties"]["name"]: shape(f["geometry"]).buffer(0) for f in zones}
    everything = unary_union(list(by_name.values()))
    hoods = [(f["properties"]["NAME"], shape(f["geometry"]).buffer(0))
             for f in json.load(open(ASSETS / "atlanta_neighborhoods.geojson"))["features"]]

    print("Loading reference layers ...")
    aps_refs = {
        "APS 2023-24 Midtown plan": [(f["properties"]["Elementary"], shape(f["geometry"]).buffer(0))
                                       for f in features(f"{APS}/MidtownESZones2023_24/FeatureServer/0")],
        "APS 2006-07 zones": [(f["properties"]["SCHOOL_NAM"], shape(f["geometry"]).buffer(0))
                                for f in features(f"{APS}/APSElementaryZones/FeatureServer/0")],
    }
    points = [(f["properties"].get("New_Label") or f["properties"].get("LABEL") or "", shape(f["geometry"]))
              for f in features(f"{APS}/Elementary_Schools/FeatureServer/0")]
    dekalb_schools = [(f["properties"]["Name"], f["properties"], shape(f["geometry"]))
                      for f in features(f"{DEKALB}/schools/FeatureServer/19") if f["properties"].get("Type") == "ES"]

    for query in wanted:
        hit = next((n for n in by_name if n.lower().startswith(query.lower())), None)
        if not hit:
            print(f"\n{query}: no such zone in school_zones.geojson")
            continue
        zone = by_name[hit]
        district = next(f["properties"]["district"] for f in zones if f["properties"]["name"] == hit)
        print(f"\n=== {hit} ({district}): {km2(zone):.2f} km2 ===")

        if district.startswith("Atlanta"):
            for label, refs in aps_refs.items():
                best = sorted(((iou(zone, g), n) for n, g in refs), reverse=True)[:1]
                print(f"  vs {label}: best match {[(round(v, 2), n) for v, n in best]}")
            for name, p in points:
                if matches(hit, name):
                    print(f"  school building '{name}' inside its zone: {zone.contains(p)}")
                    break
        elif district.startswith("DeKalb"):
            row = get(f"{DEKALB}/Elementary_School_Attendance_Areas/FeatureServer/0/query?"
                      + urllib.parse.urlencode({"where": f"ES_Name = '{hit.removesuffix(' ES')} ES'", "outFields": "ES_Num,GaDOE_Sch4", "returnGeometry": "false", "f": "json"}))
            attrs = row["features"][0]["attributes"] if row.get("features") else {}
            if attrs:
                blocks = features(f"{DEKALB}/CensusBlocks20_ESMSHS/FeatureServer/9", f"ES_Num={attrs['ES_Num']}", "GEOID")
                built = unary_union([shape(f["geometry"]).buffer(0) for f in blocks])
                print(f"  rebuilt from {len(blocks)} census blocks: overlap with polygon {iou(zone, built):.3f} "
                      f"(polygon-only {km2(zone.difference(built)):.2f} km2, blocks-only {km2(built.difference(zone)):.2f} km2)")
            for name, props, p in dekalb_schools:
                if matches(hit, name):
                    print(f"  school building '{name}' inside its zone: {zone.contains(p)}; "
                          f"state code {props.get('GaDOE_Sch4')} vs zone layer {attrs.get('GaDOE_Sch4')}")
                    break
        else:
            print("  (no independent reference wired up for this district)")

        shares = sorted(((zone.intersection(h).area / h.area, n) for n, h in hoods if h.area and zone.intersection(h).area / h.area > 0.05), reverse=True)
        print("  Atlanta neighborhoods inside: " + ("; ".join(f"{n} {round(100 * s)}%" for s, n in shares) or "none (zone is outside the city)"))
        touching = [n for n, g in by_name.items() if n != hit and zone.buffer(1e-5).intersects(g)]
        print(f"  touches: {', '.join(touching)}")
        gap = zone.buffer(0.0015).difference(zone).difference(everything)
        pieces = [g for g in (gap.geoms if hasattr(gap, "geoms") else [gap]) if g.area * KX * KY > 5000]
        big = ", ".join(f"{km2(g):.2f} km2 near {g.representative_point().y:.3f},{g.representative_point().x:.3f}" for g in pieces if km2(g) > 0.05)
        print(f"  uncovered ground within 150 m of its edge: {sum(km2(g) for g in pieces):.3f} km2 total" + (f"; larger pieces: {big}" if big else ""))


if __name__ == "__main__":
    main()
