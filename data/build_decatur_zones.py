#!/usr/bin/env python3
"""Reads City Schools of Decatur's elementary zones out of the district's official 2025-26 boundary map (a PDF).

City Schools of Decatur is its own school system (not part of DeKalb's) and publishes no GIS layer for its zones, only
this map, so the zones are traced from it:

  1. The PDF is vector artwork exported from ArcGIS Pro, so the zone shapes come out exactly as drawn:
       Lower elementary (K-2): five filled shapes (Clairemont, Glennwood, Oakhurst, Westchester, Winnona Park).
       Upper elementary (3-5): two outlines (Fifth Avenue, Talley Street).
     Each is found by matching its legend swatch, so nothing depends on drawing order.
  2. The page is placed on the real map by fitting it (scale and shift; the page is north-up) to the outline of the
     City Schools of Decatur district, an independent GIS layer. The fit must reach 97% overlap or the script stops.
  3. Optional check (--validate): every street-name label on the page is placed and measured against the real street
     of that name. On the 2025-26 map: median 2 m, 90% within 5 m over ~300 labels.

Used by build_school_zones.py. Run directly to see the report:  .venv/bin/python data/build_decatur_zones.py --validate
Needs: pip install pymupdf shapely
"""
import json
import re
import statistics
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import fitz  # PyMuPDF
from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, shape
from shapely.ops import polygonize, unary_union

ROOT = Path(__file__).resolve().parent
PDF = ROOT / "source" / "decatur-elementary-2025-26.pdf"
PDF_URL = "https://resources.finalsite.net/images/v1738775259/csdecaturnet/hq8xaabacq0pxl1hs9pj/CODBoundaryMapwSchools25-26.pdf"
DISTRICT_URL = "https://services3.arcgis.com/KkgpUIloCxkczPwY/arcgis/rest/services/City_Schools_of_Decatur_Boundary/FeatureServer/19"
KX, KY = 92_400.0, 110_540.0  # meters per degree of longitude / latitude near Atlanta
MIN_FIT_IOU = 0.97

LOWER = ["Clairemont", "Glennwood", "Oakhurst", "Westchester", "Winnona Park"]
UPPER = ["Fifth Avenue", "Talley Street"]
LEGEND_KEYWORDS = {"Clairemont Elementary": "Clairemont", "Glennwood Elementary": "Glennwood", "Oakhurst Elementary": "Oakhurst",
                   "Westchester Elementary": "Westchester", "Winnona Elementary": "Winnona Park",
                   "Fifth Avenue": "Fifth Avenue", "Talley Street": "Talley Street"}


def col(c):
    return None if c is None else tuple(round(x, 2) for x in c)


def flatten(items, steps=8):
    """PDF path items (lines, curves, rectangles) -> closed rings of (x, y) points."""
    rings, cur = [], []

    def add(pt):
        if not cur or abs(cur[-1][0] - pt[0]) > 1e-6 or abs(cur[-1][1] - pt[1]) > 1e-6:
            cur.append(pt)

    def restart(pt):
        nonlocal cur
        if cur and (abs(cur[-1][0] - pt[0]) > 1e-3 or abs(cur[-1][1] - pt[1]) > 1e-3):
            rings.append(cur)
            cur = []

    for it in items:
        if it[0] == "l":
            restart((it[1].x, it[1].y))
            add((it[1].x, it[1].y)); add((it[2].x, it[2].y))
        elif it[0] == "c":
            p0, p1, p2, p3 = it[1:5]
            restart((p0.x, p0.y))
            add((p0.x, p0.y))
            for s in range(1, steps + 1):
                t = s / steps
                m = 1 - t
                add((m**3 * p0.x + 3 * m * m * t * p1.x + 3 * m * t * t * p2.x + t**3 * p3.x,
                     m**3 * p0.y + 3 * m * m * t * p1.y + 3 * m * t * t * p2.y + t**3 * p3.y))
        elif it[0] == "re":
            r = it[1]
            if cur: rings.append(cur); cur = []
            rings.append([(r.x0, r.y0), (r.x1, r.y0), (r.x1, r.y1), (r.x0, r.y1)])
    if cur:
        rings.append(cur)
    return [r for r in rings if len(r) >= 3]


def path_polygon(path):
    """A filled path as a polygon (even-odd, so holes work)."""
    shape_ = None
    for ring in flatten(path["items"]):
        pg = Polygon(ring).buffer(0)
        if not pg.is_empty:
            shape_ = pg if shape_ is None else shape_.symmetric_difference(pg)
    return shape_


def outline_polygon(path):
    """A stroked outline as the polygon it encloses."""
    lines = []
    for ring in flatten(path["items"]):
        lines.append(LineString(ring))
    polys = list(polygonize(unary_union(lines)))
    return max(polys, key=lambda p: p.area) if polys else None


def read_pdf_zones():
    """{name: polygon in PDF points} for the seven zones, found through the legend."""
    if not PDF.exists():
        PDF.parent.mkdir(exist_ok=True)
        print(f"downloading {PDF.name} ...")
        urllib.request.urlretrieve(PDF_URL, PDF)
    page = fitz.open(PDF)[0]
    drawings = page.get_drawings()
    legend = {}
    for b in page.get_text("dict")["blocks"]:
        for line in b.get("lines", []):
            text = "".join(s["text"] for s in line["spans"]).strip()
            if text in LEGEND_KEYWORDS and line["bbox"][0] > 2100 and line["bbox"][1] > 2800:
                legend[LEGEND_KEYWORDS[text]] = (line["bbox"][1] + line["bbox"][3]) / 2
    if set(legend) != set(LOWER + UPPER):
        raise SystemExit(f"legend not found as expected (got {sorted(legend)}); the PDF layout has changed")

    def swatch(y):
        for p in drawings:
            r = p["rect"]
            if 2100 < r.x0 < 2140 and r.width < 40 and abs((r.y0 + r.y1) / 2 - y) < 12 and (p.get("fill") or p.get("color")):
                yield p

    zones = {}
    for name in LOWER:
        fill = next(col(p["fill"]) for p in swatch(legend[name]) if p.get("fill"))
        big = [p for p in drawings if col(p.get("fill")) == fill and p["rect"].width * p["rect"].height > 3e5]
        zones[name] = path_polygon(max(big, key=lambda p: p["rect"].width * p["rect"].height))
    for name in UPPER:
        stroke = next(col(p["color"]) for p in swatch(legend[name]) if p.get("color") and not p.get("fill") and (p.get("width") or 0) >= 1.5)
        big = [p for p in drawings if col(p.get("color")) == stroke and (p.get("width") or 0) > 1.5 and p["rect"].width > 500]
        zones[name] = outline_polygon(max(big, key=lambda p: len(p["items"])))
    return zones


def district_outline():
    q = urllib.parse.urlencode({"where": "1=1", "outFields": "*", "outSR": 4326, "f": "geojson"})
    req = urllib.request.Request(f"{DISTRICT_URL}/query?{q}", headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return unary_union([shape(f["geometry"]).buffer(0) for f in json.load(r)["features"]])


class Fit:
    """pdf points -> local meters -> lon/lat. The page is north-up, so it's a scale, a flip and a shift."""

    def __init__(self, lower_union, boundary):
        self.lon0, self.lat0 = boundary.centroid.x, boundary.centroid.y
        self.pc = lower_union.centroid
        self.boundary_m = affinity.affine_transform(boundary, [KX, 0, 0, KY, -self.lon0 * KX, -self.lat0 * KY])
        self.lower_union = lower_union
        best, best_score, step = [0.594, 0.0, 0.0], 0.0, [0.004, 60.0, 60.0]
        best_score = self._score(best)
        while step[1] > 0.25:
            improved = False
            for i in range(3):
                for sign in (-1, 1):
                    trial = best[:]
                    trial[i] += sign * step[i]
                    score = self._score(trial)
                    if score > best_score + 1e-6:
                        best, best_score, improved = trial, score, True
            if not improved:
                step = [x / 2 for x in step]
        self.scale, self.dx, self.dy, self.iou = best[0], best[1], best[2], best_score

    def _to_m(self, g, p=None):
        s, dx, dy = p or (self.scale, self.dx, self.dy)
        return affinity.affine_transform(g, [1 / s, 0, 0, -1 / s, -self.pc.x / s + dx, self.pc.y / s + dy])

    def _score(self, p):
        t = self._to_m(self.lower_union, p)
        return t.intersection(self.boundary_m).area / t.union(self.boundary_m).area

    def to_lonlat(self, g):
        m = self._to_m(g)
        return affinity.affine_transform(m, [1 / KX, 0, 0, 1 / KY, self.lon0, self.lat0])

    def pdf_point_m(self, x, y):
        s = self.scale
        return ((x - self.pc.x) / s + self.dx, -(y - self.pc.y) / s + self.dy)


def decatur_zones():
    """[(name, tier, polygon in lon/lat)], tier 'lower' (K-2, filled) or 'upper' (3-5, outline). Raises if the fit is poor."""
    zones = read_pdf_zones()
    fit = Fit(unary_union([zones[n] for n in LOWER]), district_outline())
    if fit.iou < MIN_FIT_IOU:
        raise SystemExit(f"Decatur map fit is only {fit.iou:.3f} against the district outline; not trusting it")
    out = [(f"{n} ES (K-2)", "lower", fit.to_lonlat(zones[n])) for n in LOWER]
    out += [(f"{n} (3-5)", "upper", fit.to_lonlat(zones[n])) for n in UPPER]
    return out, fit, zones


ABBR = {"st": "street", "ave": "avenue", "av": "avenue", "dr": "drive", "rd": "road", "ln": "lane", "ct": "court", "cir": "circle",
        "pl": "place", "blvd": "boulevard", "pkwy": "parkway", "ter": "terrace", "trl": "trail", "hwy": "highway"}
DIRS = {"n", "s", "e", "w", "ne", "nw", "se", "sw", "north", "south", "east", "west", "northeast", "northwest", "southeast", "southwest"}
GENERIC = set(ABBR.values()) | {"way", "point", "square"}


def street_key(name):
    toks = [ABBR.get(t, t) for t in re.sub(r"[^a-z0-9 ]", " ", name.lower()).split()]
    return " ".join(t for t in toks if t not in DIRS)


def validate(fit):
    """Independent accuracy check: street-name labels on the page vs the real streets they name (OpenStreetMap)."""
    sys.path.insert(0, str(ROOT))
    import build_road_graph as brg
    streets = {}
    for _ids, coords, name, _cls in brg.read_ways():
        if not name:
            continue
        lat = sum(c[0] for c in coords) / len(coords)
        lon = sum(c[1] for c in coords) / len(coords)
        if abs(lon - fit.lon0) * KX > 6000 or abs(lat - fit.lat0) * KY > 6000:
            continue
        streets.setdefault(street_key(name), []).append(LineString([((lo - fit.lon0) * KX, (la - fit.lat0) * KY) for la, lo in coords]))
    page = fitz.open(PDF)[0]
    errors = []
    for b in page.get_text("dict")["blocks"]:
        for line in b.get("lines", []):
            text = "".join(s["text"] for s in line["spans"]).strip()
            x0, y0, x1, y1 = line["bbox"]
            if len(text) < 6 or (x0 > 2050 and y0 > 2690):
                continue
            key = street_key(text)
            if key in streets and any(w not in GENERIC and len(w) > 2 for w in key.split()):
                X, Y = fit.pdf_point_m((x0 + x1) / 2, (y0 + y1) / 2)
                errors.append(min(ls.distance(Point(X, Y)) for ls in streets[key]))
    errors.sort()
    print(f"label check: {len(errors)} street labels; median {statistics.median(errors):.0f} m, "
          f"75th pct {errors[int(.75 * len(errors))]:.0f} m, 90th pct {errors[int(.9 * len(errors))]:.0f} m")
    return errors


def main():
    zones, fit, pdf_zones = decatur_zones()
    print(f"fit: {fit.scale:.4f} pt/m, shift ({fit.dx:.1f}, {fit.dy:.1f}) m, overlap with district outline {fit.iou:.4f}")
    lower = unary_union([z for _n, t, z in zones if t == "lower"])
    upper = unary_union([z for _n, t, z in zones if t == "upper"])
    print(f"K-2 zones tile: {len(LOWER)} shapes, total {lower.area * KX * KY / 1e6:.2f} km2; "
          f"3-5 zones total {upper.area * KX * KY / 1e6:.2f} km2")
    for name, tier, g in zones:
        print(f"  {name:26s} {tier:5s} {g.area * KX * KY / 1e6:5.2f} km2")
    if "--validate" in sys.argv:
        validate(fit)


if __name__ == "__main__":
    main()
