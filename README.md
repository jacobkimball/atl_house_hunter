# House Hunter

Android app for choosing which Atlanta neighborhood to live in. See [requirements.md](requirements.md) for what it should do.

## Data sources & licenses

**Disclaimer:** this is a personal project. The map data, and the school zones in particular, are approximate and may be
out of date: some zones are traced from PDF maps, and districts redraw zones over time. Always confirm a school
assignment with the school district before relying on it. Nothing here is affiliated with or endorsed by any of the
sources below.

| Data | Source | Notes |
|---|---|---|
| Streets, railroads, MARTA stations, place names, and everything built from them (`road_graph.bin`, `rail.geojson`, `stations.geojson`, `search_index.json`, `osm_neighborhoods.geojson`) | © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) | Available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/). The files derived from it are shared under the same license. |
| Basemap tiles (`atlanta.pmtiles`) | [Protomaps](https://protomaps.com) daily build of OpenStreetMap | ODbL, as above. |
| Map label fonts (`glyphs/`) | Noto Sans, via [Protomaps basemaps-assets](https://github.com/protomaps/basemaps-assets) | SIL Open Font License; see `glyphs/OFL.txt`. |
| Atlanta neighborhoods | City of Atlanta Department of City Planning open data | Official neighborhood boundaries. |
| Sandy Springs and Brookhaven subdivisions | City of Sandy Springs and City of Brookhaven GIS open data | |
| Elementary school zones: DeKalb | DeKalb County School District (its own published zone layer) | |
| Elementary school zones: Fulton | Fulton County Schools (its own published zone layer, 2026-27) | |
| Elementary school zones: Atlanta Public Schools | A public ArcGIS layer compiled by a third party (`APS_elementary_zones_with_school_data`, Aug 2026) | Not an official APS file. Credit to its compiler. |
| Elementary school zones: City Schools of Decatur | Traced from the district's official 2025-26 boundary map (PDF) | Placement checked to within a few meters; see `data/build_decatur_zones.py`. |
| Elementary school zones: Cobb County | Traced from the district's official 2023-24 elementary zone map (PDF) | Older than the other zone data; see `data/build_cobb_zones.py`. |
| Elementary school zones: Marietta City Schools | Built from the City of Marietta's public parcel layer | The city states that users must verify any information derived from its GIS data. |

The scripts in `data/` re-download and rebuild all of the above; each one's header documents its source and method.
School ratings (e.g. GreatSchools) are deliberately not included: their terms don't allow bundling them.

## Distributing an APK

To build a signed APK you can send to someone else (not through the Play Store):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. It's signed with `keystore/release.jks` (gitignored, along with
`keystore.properties` which holds its passwords — back both up somewhere safe; losing them means a future update can't
be signed to match and users would have to uninstall/reinstall). The person installing it needs to allow "install
unknown apps" for whatever app they downloaded it through, since it isn't from the Play Store.

## Build and run

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Emulator: `emulator -avd house_hunter_test` (needs `hw.gpu.enabled=yes` in its `config.ini`, or the map renders in software and hangs).
Fake a GPS fix with `adb emu geo fix -84.388 33.749`.

## Map data

The app is offline-first. `data/assets/` holds the basemap (`atlanta.pmtiles`, OpenStreetMap via Protomaps) and neighborhood polygons (`atlanta_neighborhoods.geojson`, City of Atlanta). Regenerate with `data/build_data.sh`. The street network for snap-to-street (`road_graph.bin`, 7 MB) is built separately by `data/build_road_graph.py` from an OpenStreetMap Georgia extract (`data/source/georgia-latest.osm.pbf`, ~350 MB from download.geofabrik.de, not committed). The map look (high-traffic roads, rail, neighborhoods) lives in `app/src/main/assets/style.json`.

App icon: an adaptive icon drawn as vectors in `app/src/main/res/drawable/ic_launcher_*.xml` (teal background with faint streets, white house, pink map pin, plus a one-color version for Android 13+ themed icons). Edit those files to change it.

## Status

Done: offline map with labels; neighborhoods (Atlanta official, Sandy Springs/Brookhaven subdivisions, OSM name labels elsewhere), high-traffic roads, rail; pan/zoom; my location.

Markers and notes (requirements §8): Mark button, crosshair, one-finger drag moves the dot, Mark again places the pin, notepad opens, Save stores the note; tap a pin to reopen it; delete with confirmation; unsaved-edit prompt. Saved to `markers.json` in app storage (a plain JSON file, not Room; simple and enough for this data size).

Pen and eraser (requirements §6-7): six-color highlighter (red, orange, yellow, green, blue, purple); one finger draws, two fingers pinch/pan. Highlights are stored as map coordinates (`strokes.json`), drawn under the street labels, and stay put as you pan and zoom. The eraser is a true partial eraser: it cuts strokes apart wherever the circle passes. Undo/redo (50 steps) and Clear all (with confirmation).

Snap to street (PEN-3 to PEN-6): a "Snap to streets" toggle above the palette (on by default). Drag roughly along a street and the highlight follows its centerline through curves and intersections; a drag farther than 28 dp from any street draws nothing. Off = freehand. How it works: `RoadGraph` (streets cut into edges at junctions, with a spatial index), `SnapMatcher` (live matching as you draw, plus a whole-stroke Viterbi pass when the finger lifts that drops wobbles onto side streets). On the real Atlanta network with a drifting finger the tests measure about 99.5% of the traced line on the intended route and 99.5% of the route traced, even with the finger wandering up to 26 m off.

Layers and map key (MAP-7): a Layers button opens switches for neighborhoods, high-traffic roads, railroads & MARTA, highlights and markers, each with a legend swatch; choices are saved (`AppPrefs`). Rail comes from `data/build_rail.py` (freight vs MARTA vs yards, classified from OSM tags). Tapping a neighborhood shows its name (MAP-8). About & data sources gives the attribution (MAP-9).

Backup and restore (Q11): Layers → Back up & restore. Export writes one JSON file via Android's file picker (no storage permission needed); Import validates it first, then adds to or replaces the phone's data. Format and merge rules are in `Backup.kt`, with tests. Cloud auto-backup is limited to `markers.json`, `strokes.json` and settings by `res/xml/backup_rules.xml` and `data_extraction_rules.xml` (the map files are too big and can be rebuilt).

Search (Q12): Search button beside Layers. `data/build_search_index.py` builds `search_index.json` (about 26,000 entries, 2.6 MB: neighborhoods, subdivisions, cities, MARTA stations, streets with the neighborhood they're in and their length for ranking). The app reads it the first time you open search; `SearchIndex.kt` does the matching (abbreviations, word order, accents, prefixes) and also searches your notes.

Drawing layers: up to 4 named layers with their own switches (`DrawingLayers` rules in `Stroke.kt`, names in `layers.json`, per-layer visibility and the active layer in `AppPrefs`). Backup format is now version 2 (adds layers; version 1 files still import). Navigate to here: `MapScreen.navigateTo` fires a `google.navigation:` intent at Google Maps, with a browser fallback; `AndroidManifest.xml` declares the `<queries>` needed to find Maps.

School zones: `data/build_school_zones.py` fetches elementary zones (DeKalb County School District, Fulton County Schools, and an Atlanta Public Schools compilation), clips them to the map area, checks they don't overlap across districts, and colors them so neighbors differ. Data sources and the reasons behind each choice are in the script's header. City Schools of Decatur is traced from its official boundary PDF by `data/build_decatur_zones.py` (reads the vector shapes, fits them to Decatur's district outline, refuses if the fit is below 97%; `--validate` checks street labels against real streets). Cobb County School District is traced from its 2023-24 countywide zone PDF by `data/build_cobb_zones.py` (matches ~460,000 road points drawn on the page to OpenStreetMap roads; run it directly to see the accuracy report). Marietta City Schools is built by `data/build_marietta_zones.py` from the City of Marietta's parcel layer (each parcel records its elementary school), and Cobb's zones are trimmed to meet it. Clayton and Gwinnett are not covered. Ratings are not included (GreatSchools' terms forbid bundling them).

Checking the school zones: `data/verify_school_zones.py [school ...]` compares zones with independent official data (APS's own planning layers, DeKalb's census-block assignments, school building locations) and reports overlaps, neighbors and edge gaps. It shows the layer agrees with other data, not what a district decided after the latest files.

Not yet built: moving a placed marker, dark theme, color-blind-safe highlight outlines. Not yet exercised on real hardware: feel of snapping under a real thumb.

Tests: `./gradlew :app:testDebugUnitTest` (60 tests: eraser geometry, path simplifier, snap matcher on synthetic grids and on the real street file, backup format and merge, search, drawing layers).

Emulator tips: `am start -n com.househunter.app/.MainActivity --ef lat 33.775 --ef lon -84.295 --ef zoom 14.3` opens the map at a chosen camera (adb can't pinch). When more than one device is attached, pass `-s <serial>` to every adb command. After a swipe, wait ~5 s before screenshotting: the emulator is slow to repaint, and it can deliver far fewer touch events than a real finger.
