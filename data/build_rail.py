#!/usr/bin/env python3
"""Builds the railroad and MARTA-station overlays: data/assets/rail.geojson and data/assets/stations.geojson.

Input: data/source/georgia-latest.osm.pbf (see build_road_graph.py).

Why not just use the rail in the basemap tiles? They can't tell a freight main line from MARTA, or a yard from
either, and that difference matters when deciding where to live. Each way is classified by OSM's own tags:

  freight        railway=rail, usage main/branch: CSX and Norfolk Southern main lines (the noisy ones)
  freight-minor  railway=rail that is a yard, spur, siding, crossover or industrial track
  marta          railway=subway, at ground level or elevated
  marta-tunnel   railway=subway, underground (tunnel=yes)
  marta-minor    MARTA yard, siding or crossover track

Abandoned, disused and razed track (including old BeltLine corridors) and the airport monorail are left out.

Run:  .venv/bin/python data/build_rail.py
"""
import json
from pathlib import Path

import osmium

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "source" / "georgia-latest.osm.pbf"
OUT = ROOT / "assets"
BBOX = (-84.60, 33.60, -84.20, 34.02)  # lon_min, lat_min, lon_max, lat_max; keep in sync with build_data.sh

OPERATORS = {
    "CSX Transportation": "CSX",
    "Norfolk Southern Railway": "Norfolk Southern",
    "Fulton County Railway": "Fulton County Railway",
    "Georgia Northeastern Railroad": "Georgia Northeastern",
    "Metropolitan Atlanta Rapid Transit Authority": "MARTA",
    "MARTA": "MARTA",
}
MINOR_SERVICE = {"yard", "spur", "siding", "crossover"}


def classify(tags):
    railway = tags.get("railway")
    service = tags.get("service")
    if railway == "rail":
        if service in MINOR_SERVICE or tags.get("usage") == "industrial":
            return "freight-minor"
        if tags.get("usage") in ("main", "branch"):
            return "freight"
        return "freight-minor"
    if railway == "subway":
        if service in MINOR_SERVICE:
            return "marta-minor"
        return "marta-tunnel" if tags.get("tunnel") == "yes" else "marta"
    return None


def main():
    lon0, lat0, lon1, lat1 = BBOX
    lines, stations = [], []
    processor = osmium.FileProcessor(str(SOURCE)).with_locations().with_filter(osmium.filter.KeyFilter("railway"))
    for obj in processor:
        tags = obj.tags
        if obj.is_way():
            kind = classify(tags)
            if kind is None:
                continue
            pts = [(n.location.lon, n.location.lat) for n in obj.nodes if n.location.valid()]
            if len(pts) < 2 or not any(lon0 <= x <= lon1 and lat0 <= y <= lat1 for x, y in pts):
                continue
            operator = OPERATORS.get(tags.get("operator", ""), tags.get("operator", ""))
            lines.append({"type": "Feature",
                          "properties": {"kind": kind, "operator": operator},
                          "geometry": {"type": "LineString", "coordinates": [[round(x, 6), round(y, 6)] for x, y in pts]}})
        elif obj.is_node() and tags.get("railway") == "station" and tags.get("station") == "subway" \
                and tags.get("network") == "MARTA" and tags.get("name"):
            loc = obj.location
            if loc.valid() and lon0 <= loc.lon <= lon1 and lat0 <= loc.lat <= lat1:
                name = tags["name"].removesuffix(" Station")
                stations.append({"type": "Feature", "properties": {"name": name},
                                 "geometry": {"type": "Point", "coordinates": [round(loc.lon, 6), round(loc.lat, 6)]}})

    (OUT / "rail.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": lines}, separators=(",", ":")))
    (OUT / "stations.geojson").write_text(json.dumps({"type": "FeatureCollection", "features": stations}, separators=(",", ":")))
    counts = {}
    for f in lines:
        counts[f["properties"]["kind"]] = counts.get(f["properties"]["kind"], 0) + 1
    print("rail.geojson:", len(lines), "lines", counts)
    print("stations.geojson:", len(stations), "MARTA stations:", ", ".join(sorted(s["properties"]["name"] for s in stations)))


if __name__ == "__main__":
    main()
