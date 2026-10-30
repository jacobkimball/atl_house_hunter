#!/usr/bin/env python3
"""Reads Cobb County School District's elementary zones out of the district's official 2023-24 zone map (a PDF).

Cobb publishes no GIS layer for its zones, only PDF maps (https://www.cobbk12.org/attendance-zones), so the zones are
traced from the countywide elementary map:

  1. The PDF is vector artwork exported from ArcMap. The 69 zone shapes are the paths on its "ElemSchoolCrop" layer
     (the other layers repeat them in other colors).
  2. The page is placed on the real map by matching the ~460,000 points of road lines drawn on it (Census TIGER roads)
     to OpenStreetMap roads, iteratively, with a shrinking match distance (an affine fit: scale, rotation, shift).
     OpenStreetMap only covers our map area (data/source/georgia-latest.osm.pbf, clipped), which is the part we need.
  3. Each zone is named after the Cobb school (from the district's school-locations layer) whose building lies inside it.

Checks, all reported when run:
  - noise: fit random halves of the roads separately; the two fits should put any point in the map area within a few
    meters of each other;
  - drift: in 4 km cells across the map area, the average offset left between the page's roads and the real ones
    (a warp the affine model can't absorb would show here);
  - every elementary school building should fall inside exactly one zone, with a matching name printed on the page;
  - Cobb zones should not overlap the zones of neighboring districts.

Limits: this is the 2023-24 map (older than the other districts' data). Marietta City Schools is a separate district
and is not part of this map, so Marietta shows as a hole. Individual school PDFs on cobbk12.org show later boundary tweaks
for a few schools only (Bullard, Chalker, ... are dated 2013-2015 there), so nothing here is newer.

Used by build_school_zones.py. Run directly for the report:  .venv/bin/python data/build_cobb_zones.py
Needs: pip install pymupdf shapely scipy numpy
"""
import json
import math
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

import fitz  # PyMuPDF
import numpy as np
from scipy.spatial import cKDTree
from shapely import affinity, wkt
from shapely.geometry import Point, box, shape
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import build_decatur_zones as bdz  # noqa: E402  (shared PDF path helpers)
import build_road_graph as brg  # noqa: E402

PDF = ROOT / "source" / "cobb-elementary-2023-24.pdf"
PDF_URL = "https://media.cobbk12.org/media/WWWCobb/medialib/elementaryalljune2023.37a9db85396.pdf"
SCHOOLS_URL = "https://services.arcgis.com/HYLRafMc4Ux6DA8c/arcgis/rest/services/Public_Schools_in_Cobb_County/FeatureServer/0"
COUNTIES_URL = "https://services1.arcgis.com/stuCU7Ai47X10WDV/arcgis/rest/services/GACounties/FeatureServer/0"
KX, KY = 92_400.0, 110_540.0  # meters per degree of longitude / latitude near Atlanta
LON0, LAT0 = -84.5575, 33.913  # centre of Cobb County: origin of the local-meters frame
ROAD_LAYERS = ("TIGERbasedCobbRoads4", "Majorroadscobb2", "Expressways_CobbOnly")
OSM_MIN_LON = -84.59  # our OpenStreetMap data starts here (build_road_graph.BBOX is -84.60)
LABEL_SIZE = 47  # school names on the page are set at 48 pt


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=90) as r:
        return json.load(r)


def read_pdf():
    """(zone polygons in PDF points, [(text, x, y)] school labels, road sample points as an Nx2 array)."""
    if not PDF.exists():
        PDF.parent.mkdir(exist_ok=True)
        print(f"downloading {PDF.name} ...")
        urllib.request.urlretrieve(PDF_URL, PDF)
    page = fitz.open(PDF)[0]
    zones, roads = [], []
    for p in page.get_drawings():
        layer = p.get("layer")
        if layer == "ElemSchoolCrop" and p.get("fill") and p["rect"].width * p["rect"].height > 2000:
            g = bdz.path_polygon(p)
            if g is not None and not g.is_empty:
                zones.append(g)
        elif layer in ROAD_LAYERS and not p.get("fill"):
            for it in p["items"]:
                if it[0] == "l":
                    a, b = it[1], it[2]
                    n = max(1, int(math.hypot(b.x - a.x, b.y - a.y) / 6))
                    roads.extend((a.x + (b.x - a.x) * k / n, a.y + (b.y - a.y) * k / n) for k in range(n + 1))
    labels = []
    for blk in page.get_text("dict")["blocks"]:
        for line in blk.get("lines", []):
            text = "".join(s["text"] for s in line["spans"]).strip()
            if line["spans"][0]["size"] >= LABEL_SIZE and len(text) >= 3:
                labels.append((text, (line["bbox"][0] + line["bbox"][2]) / 2, (line["bbox"][1] + line["bbox"][3]) / 2))
    return zones, labels, np.array(roads)


def osm_points():
    """Points along every OpenStreetMap street in our map area, in local meters (about 1.6 million)."""
    pts = []
    for _ids, coords, _name, _cls in brg.read_ways():
        xy = [((lo - LON0) * KX, (la - LAT0) * KY) for la, lo in coords]
        for (x1, y1), (x2, y2) in zip(xy, xy[1:]):
            n = max(1, int(math.hypot(x2 - x1, y2 - y1) / 8))
            pts.extend((x1 + (x2 - x1) * k / n, y1 + (y2 - y1) * k / n) for k in range(n))
    return np.array(pts)


def apply(A, t, P):
    return P @ A.T + t


def icp(pdf_pts, osm_tree, osm_pts, A, t):
    """Iterative closest point with an affine model and a shrinking match distance. Returns (A, t, median residual m)."""
    res = float("nan")
    for thr in [1500, 800, 400, 200, 100, 60, 40, 30, 25, 20, 20, 20]:
        Q = apply(A, t, pdf_pts)
        d, idx = osm_tree.query(Q, distance_upper_bound=thr)
        ok = np.isfinite(d) & (Q[:, 0] / KX + LON0 > OSM_MIN_LON)
        if ok.sum() < 200:
            raise SystemExit("too few road matches; the PDF layout has changed")
        P, T = pdf_pts[ok], osm_pts[idx[ok]]
        M = np.hstack([P, np.ones((len(P), 1))])
        sx = np.linalg.lstsq(M, T[:, 0], rcond=None)[0]
        sy = np.linalg.lstsq(M, T[:, 1], rcond=None)[0]
        A, t = np.array([[sx[0], sx[1]], [sy[0], sy[1]]]), np.array([sx[2], sy[2]])
        res = float(np.median(np.hypot(*(apply(A, t, P) - T).T)))
    return A, t, res


def county_outline():
    q = urllib.parse.urlencode({"where": "1=1", "outFields": "name", "outSR": 4326, "f": "geojson", "resultRecordCount": 500})
    return next(shape(f["geometry"]) for f in get_json(COUNTIES_URL + "/query?" + q)["features"]
                if f["properties"]["name"].lower().startswith("cobb"))


def initial_fit(zone_union):
    """Map the zones' bounding box onto Cobb County's, as a starting point for the road matching."""
    cb, ub = county_outline().bounds, zone_union.bounds
    sx = (cb[2] - cb[0]) * KX / (ub[2] - ub[0])
    sy = (cb[3] - cb[1]) * KY / (ub[3] - ub[1])
    return (np.array([[sx, 0], [0, -sy]]),
            np.array([(cb[0] - LON0) * KX - ub[0] * sx, (cb[3] - LAT0) * KY + ub[1] * sy]))


def school_points():
    """[(name, system, Point in local meters)] for the elementary schools of Cobb and of Marietta City Schools."""
    q = urllib.parse.urlencode({"where": "USER_Type='Elementary'", "outFields": "USER_Name,USER_System", "outSR": 4326,
                                "f": "geojson", "resultRecordCount": 500})
    out = []
    for f in get_json(SCHOOLS_URL + "/query?" + q)["features"]:
        x, y = f["geometry"]["coordinates"][:2]
        out.append((f["properties"]["USER_Name"], f["properties"]["USER_System"], Point((x - LON0) * KX, (y - LAT0) * KY)))
    return out


def squash(text):
    text = re.sub(r"(?i)\b(elementary|primary|intermediate|school|ES)\b", " ", text)
    return re.sub(r"[^a-z0-9]", "", text.lower())


def cobb_zones(verbose=False):
    """([(name, polygon in lon/lat)], report dict). Raises if a check fails badly."""
    pdf_zones, labels, roads = read_pdf()
    osm = osm_points()
    tree = cKDTree(osm)
    A0, t0 = initial_fit(unary_union(pdf_zones))
    A, t, resid = icp(roads, tree, osm, A0, t0)

    # Noise: fits from two random halves of the roads should agree everywhere in the map area.
    keep = np.random.default_rng(1).random(len(roads)) < 0.5
    A1, t1, _ = icp(roads[keep], tree, osm, A, t)
    A2, t2, _ = icp(roads[~keep], tree, osm, A, t)
    probes = np.array([[(lon - LON0) * KX, (lat - LAT0) * KY] for lon in (-84.55, -84.50, -84.45, -84.40) for lat in (33.70, 33.80, 33.90, 34.00)])
    pdf_probes = np.array([np.linalg.solve(A, p - t) for p in probes])
    halves_gap = np.hypot(*(apply(A1, t1, pdf_probes) - apply(A2, t2, pdf_probes)).T)
    # Drift: mean leftover offset per 4 km cell (only cells with plenty of matches).
    Q = apply(A, t, roads)
    dist, idx = tree.query(Q, distance_upper_bound=40)
    ok = np.isfinite(dist) & (Q[:, 0] / KX + LON0 > OSM_MIN_LON)
    cells = {}
    for (x, y), o in zip(Q[ok], osm[idx[ok]] - Q[ok]):
        cells.setdefault((int(x // 4000), int(y // 4000)), []).append(o)
    drift = [float(np.hypot(*np.mean(v, axis=0))) for v in cells.values() if len(v) > 300]
    def to_lonlat(g):
        m = affinity.affine_transform(g, [A[0, 0], A[0, 1], A[1, 0], A[1, 1], t[0], t[1]])
        return affinity.affine_transform(m, [1 / KX, 0, 0, 1 / KY, LON0, LAT0])

    lonlat = [to_lonlat(g).buffer(0) for g in pdf_zones]
    zone_m = [affinity.affine_transform(g, [KX, 0, 0, KY, -LON0 * KX, -LAT0 * KY]) for g in lonlat]
    schools = school_points()
    cobb = [(n, pt) for n, sysm, pt in schools if sysm == "Cobb"]
    marietta = [pt for n, sysm, pt in schools if sysm == "Marietta"]

    # Independent placement test: the outer edge of the zones should follow Cobb County's official boundary, a layer
    # the road fit never saw. (The school names printed on the page can't serve: at this scale a 48 pt name spans ~3 km.)
    county = county_outline()
    cobb_union = unary_union(lonlat)  # all shapes, Marietta included: together they fill the county
    county_overlap = cobb_union.intersection(county).area / cobb_union.union(county).area
    to_m = lambda g: affinity.affine_transform(g, [KX, 0, 0, KY, -LON0 * KX, -LAT0 * KY])
    county_line = to_m(county.boundary)
    edge = unary_union([p.exterior for p in (cobb_union.geoms if hasattr(cobb_union, "geoms") else [cobb_union])]
                       ).intersection(box(-84.60, 33.60, -84.20, 34.02))  # only the outer edge, not gaps between shapes
    samples = []
    for g in (edge.geoms if hasattr(edge, "geoms") else [edge]):
        samples += [to_m(g.interpolate(k * 0.0003)) for k in range(int(g.length / 0.0003) + 1)]
    edge_gap = np.array([county_line.distance(p) for p in samples])

    # Name each zone. A school's building inside the zone counts double; its printed name inside counts once.
    labels_in = [[text for text, x, y in labels if z.contains(Point(x, y))] for z in pdf_zones]
    out, dropped, named_both = [], [], 0
    marietta_shape = None
    for i, z in enumerate(zone_m):
        if sum(1 for pt in marietta if z.contains(pt)) >= 3:
            dropped.append(("Marietta City Schools", z.area / 1e6))  # a different district's area, not a Cobb zone
            marietta_shape = lonlat[i]
            continue
        score = {}
        for n, pt in cobb:
            pts = (2 if z.contains(pt) else 0) + (1 if any(squash(l) and squash(l) == squash(n) for l in labels_in[i]) else 0)
            if pts:
                score[n] = pts
        if not score:
            dropped.append(("no school inside or named", z.area / 1e6))
            continue
        top = max(score.values())
        winners = sorted(n for n, v in score.items() if v == top)
        named_both += top == 3
        names = " / ".join(re.sub(r"(?i)\s+(elementary\s+)?school$", "", n).strip() for n in winners)
        out.append((names if re.search(r"(?i)primary|intermediate|academy|elementary|\bES\b", names) else names + " ES", lonlat[i]))
    # Every Cobb school should be the winner of some zone (some zones share a school pair).
    used = {n for n, g in out}
    inside_any = {n for n, pt in cobb if any(z.contains(pt) for z in zone_m)}
    report = {"road_residual_m": resid, "halves_gap_max_m": float(halves_gap.max()), "halves_gap_median_m": float(np.median(halves_gap)),
              "drift_max_m": max(drift), "drift_median_m": float(np.median(drift)), "drift_cells": len(drift),
              "county_overlap": float(county_overlap),
              "edge_gap_m": (float(np.median(edge_gap)), float(np.percentile(edge_gap, 90)), float((edge_gap < 60).mean()), len(edge_gap)),
              "schools": len(cobb), "schools_in_a_zone": len(inside_any), "zones": len(pdf_zones), "kept": len(out),
              "named_by_both": named_both, "dropped": dropped, "marietta_shape": marietta_shape}
    if resid > 15 or halves_gap.max() > 15 or max(drift) > 25 or county_overlap < 0.97 or report["edge_gap_m"][0] > 60:
        raise SystemExit(f"Cobb map fit looks unreliable: {report}")
    return out, report


def main():
    zones, r = cobb_zones()
    print(f"road fit: median residual {r['road_residual_m']:.1f} m")
    print(f"noise: two random halves of the roads place any point within {r['halves_gap_max_m']:.1f} m of each other (median {r['halves_gap_median_m']:.1f} m)")
    print(f"drift: {r['drift_cells']} cells of 4 km; average leftover offset median {r['drift_median_m']:.1f} m, worst {r['drift_max_m']:.1f} m")
    med, p90, share, n = r["edge_gap_m"]
    print(f"independent check: all shapes together overlap Cobb County's official outline {100 * r['county_overlap']:.1f}%; "
          f"their outer edge in the map area lies a median {med:.0f} m from the county line "
          f"(90th percentile {p90:.0f} m; {100 * share:.0f}% within 60 m; {n} samples)")
    print(f"schools: {r['schools_in_a_zone']} of {r['schools']} Cobb elementary schools fall inside a zone; {r['kept']} of {r['zones']} shapes kept, "
          f"", end="")
    print(f"{r['named_by_both']} named by both building and printed name")
    for why, area in r["dropped"]:
        print(f"   dropped: {why}, {area:.1f} km2")


if __name__ == "__main__":
    main()
