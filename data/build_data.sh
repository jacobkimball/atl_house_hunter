#!/usr/bin/env bash
# Rebuilds the map data bundled into the app (data/assets/). Needs: pmtiles (brew install pmtiles), curl.
# Usage: data/build_data.sh [YYYYMMDD]   (Protomaps daily build date; defaults to the newest)
set -euo pipefail
cd "$(dirname "$0")/assets"

# Coverage area: inside I-285 plus Sandy Springs, with a small buffer (requirements.md §2.1).
BBOX="-84.60,33.60,-84.20,34.02"

BUILD="${1:-$(curl -s https://build-metadata.protomaps.dev/builds.json | python3 -c "import sys,json; print(json.load(sys.stdin)[-1]['key'])")}"
BUILD="${BUILD%.pmtiles}"
echo "Extracting basemap from Protomaps build $BUILD ..."
pmtiles extract "https://build.protomaps.com/${BUILD}.pmtiles" atlanta.pmtiles --bbox="$BBOX"

echo "Fetching City of Atlanta official neighborhoods ..."
curl -s "https://gis.atlantaga.gov/dpcd/rest/services/AdministrativeArea/GeopoliticalArea/MapServer/1/query?where=1%3D1&outFields=NAME,NPU&outSR=4326&f=geojson&resultRecordCount=2000" \
  -o atlanta_neighborhoods.geojson
ls -lh atlanta.pmtiles atlanta_neighborhoods.geojson

echo "Building neighborhood overlays outside Atlanta (Sandy Springs, Brookhaven, OSM name points) ..."
cd ../..
.venv/bin/python data/build_neighborhoods.py

echo "Fetching elementary school zones (DeKalb, Fulton, APS) ..."
.venv/bin/python data/build_school_zones.py
