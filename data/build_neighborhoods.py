#!/usr/bin/env python3
"""Builds the neighborhood overlays outside the City of Atlanta (data/assets/*.geojson).

  subdivisions.geojson        Sandy Springs + Brookhaven official polygons, normalized.
                              scale = "neighborhood" for Sandy Springs civic associations, else "subdivision".
  osm_neighborhoods.geojson   OpenStreetMap neighborhood name points from atlanta.pmtiles that fall
                              OUTSIDE the official Atlanta polygons (inside them, the official name is used).

Needs the pmtiles CLI on PATH and `pip install mapbox-vector-tile` (the repo's .venv has it):
    .venv/bin/python data/build_neighborhoods.py
"""
import gzip
import json
import math
import subprocess
import urllib.request
import warnings
from pathlib import Path

warnings.filterwarnings("ignore")
import mapbox_vector_tile as mvt  # noqa: E402

ASSETS = Path(__file__).parent / "assets"
BBOX = (-84.60, 33.60, -84.20, 34.02)  # keep in sync with build_data.sh
QUERY = "/query?where=1%3D1&outFields=*&outSR=4326&f=geojson&resultRecordCount=2000"
SANDY_SPRINGS = "https://gis2.sandyspringsga.gov/arcgis/rest/services/OpenData/General_Reference/MapServer/125"
BROOKHAVEN = "https://maps.brookhavenga.gov/arcgis/rest/services/AdministrativeArea/AdministrativeArea/MapServer/2"


def fetch(url):
    with urllib.request.urlopen(url + QUERY, timeout=60) as r:
        return json.load(r)["features"]


def round_coords(c):
    return [round_coords(x) for x in c] if isinstance(c[0], list) else [round(c[0], 6), round(c[1], 6)]


def tidy_name(raw):
    return " ".join((raw or "").split()).title().replace("Assn", "Assn.").replace("Hoa", "HOA")


def feature(name, city, scale, geom):
    return {"type": "Feature",
            "properties": {"name": name, "city": city, "scale": scale},
            "geometry": {"type": geom["type"], "coordinates": round_coords(geom["coordinates"])}}


def build_subdivisions():
    out = []
    for f in fetch(SANDY_SPRINGS):
        name = tidy_name(f["properties"].get("Name"))
        if name and f.get("geometry"):
            scale = "neighborhood" if f["properties"].get("Type") == "Civic" else "subdivision"
            if scale == "neighborhood":
                name = name.replace(" Civic Assn.", "").strip()
            out.append(feature(name, "Sandy Springs", scale, f["geometry"]))
    for f in fetch(BROOKHAVEN):
        name = " ".join((f["properties"].get("NAME") or "").split())
        if name and f.get("geometry"):
            out.append(feature(name, "Brookhaven", "subdivision", f["geometry"]))
    return out


def point_in_ring(pt, ring):
    x, y = pt
    inside = False
    for (x1, y1), (x2, y2) in zip(ring, ring[1:]):
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
    return inside


def in_polygons(pt, features):
    for f in features:
        g = f["geometry"]
        for poly in [g["coordinates"]] if g["type"] == "Polygon" else g["coordinates"]:
            if point_in_ring(pt, poly[0]) and not any(point_in_ring(pt, h) for h in poly[1:]):
                return True
    return False


def osm_points(kinds=("neighbourhood", "macrohood")):
    z = 12
    n = 2 ** z
    tile_x = lambda lon: int((lon + 180) / 360 * n)
    tile_y = lambda lat: int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    pts = {}
    for x in range(tile_x(BBOX[0]), tile_x(BBOX[2]) + 1):
        for y in range(tile_y(BBOX[3]), tile_y(BBOX[1]) + 1):
            raw = subprocess.run(["pmtiles", "tile", str(ASSETS / "atlanta.pmtiles"), str(z), str(x), str(y)],
                                 capture_output=True).stdout
            if not raw:
                continue
            try:
                raw = gzip.decompress(raw)
            except OSError:
                pass
            for f in mvt.decode(raw, y_coord_down=True).get("places", {}).get("features", []):
                p, g = f["properties"], f["geometry"]
                if g["type"] != "Point" or p.get("kind") not in kinds or not p.get("name"):
                    continue
                px, py = g["coordinates"]
                lon = (x + px / 4096) / n * 360 - 180
                lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / 4096) / n))))
                if BBOX[0] <= lon <= BBOX[2] and BBOX[1] <= lat <= BBOX[3]:
                    pts[(p["name"], round(lon, 5), round(lat, 5))] = p["kind"]
    return pts


def main():
    subs = build_subdivisions()
    (ASSETS / "subdivisions.geojson").write_text(
        json.dumps({"type": "FeatureCollection", "features": subs}, separators=(",", ":")))
    atlanta = json.load(open(ASSETS / "atlanta_neighborhoods.geojson"))["features"]
    kept = [
        {"type": "Feature", "properties": {"name": name},
         "geometry": {"type": "Point", "coordinates": [lon, lat]}}
        for (name, lon, lat) in osm_points() if not in_polygons((lon, lat), atlanta)
    ]
    (ASSETS / "osm_neighborhoods.geojson").write_text(
        json.dumps({"type": "FeatureCollection", "features": kept}, separators=(",", ":")))
    print(f"subdivisions.geojson: {len(subs)} polygons "
          f"({sum(f['properties']['scale'] == 'neighborhood' for f in subs)} neighborhood-scale)")
    print(f"osm_neighborhoods.geojson: {len(kept)} name points outside official Atlanta polygons")


if __name__ == "__main__":
    main()
