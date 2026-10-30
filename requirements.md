# House Hunter — Requirements

**Status:** v0.3, built and running (see §14) · **Date:** 2026-09-20 · **Platform:** Android (test device runs Android 17)

**How to read this:** Every requirement is tagged with where it came from.

- **[R]** = you asked for it.
- **[P]** = I'm proposing it because the feature doesn't work well without it, or because it's a cheap and obvious addition. Strike anything you don't want.

Section 9 lists open questions. Each has my default assumption, so nothing is blocked. Just correct the ones I got wrong.

---

## 1. Purpose

A personal Android app for figuring out **which Atlanta neighborhood to live in**. It shows a map of the area with the things that matter for livability (neighborhood boundaries, busy roads, railroads), then lets me **mark up the map by hand** (highlight streets, drop notes) while I explore in person or at my desk.

**Primary user:** me, one person, one device. No accounts, no sharing, no backend (v1).

## 2. Scope

### 2.1 Geographic coverage [R]

| Included | Notes |
|---|---|
| Everything **inside the I-285 perimeter** ("ITP") | Includes all incorporated cities and unincorporated areas inside it (Atlanta, Decatur, Brookhaven, Chamblee, Dunwoody, Smyrna/Vinings, East Point, College Park, Hapeville, etc.), not just the City of Atlanta. |
| **Sandy Springs** | Extends north beyond I-285, so coverage is *ITP ∪ Sandy Springs city limits*. |

- Rough bounding box: **≈ 33.60–34.02 °N, 84.60–84.20 °W** (to be finalized against the real polygon, with a small buffer so the edges aren't abrupt).
- **"Every street"** means every publicly drivable street is on the map and labeled at high zoom, including small residential streets and cul-de-sacs. Driveways and parking-lot aisles are excluded.

### 2.2 Out of scope for v1

- Turn-by-turn navigation or routing
- Real-estate listings, prices, schools, crime, or commute overlays (natural future additions, see §10)
- iOS, tablet-optimized layouts, web
- Cloud sync, sharing, multi-user
- Anything outside the coverage area above

## 3. Map display

| ID | Requirement | Src |
|---|---|---|
| MAP-1 | Show a pannable, zoomable map of the full coverage area (§2.1). | R |
| MAP-2 | **Neighborhoods** are clearly marked: visible boundary lines, a light tint, and a name label. Labels appear/disappear by zoom level so they never clutter. | R |
| MAP-3 | **High-traffic roads** are visually distinct from ordinary streets: heavier weight and a strong, consistent color, plus a legend entry. See Open Question Q4 for the definition of "high-traffic". | R |
| MAP-4 | **Railroads** are clearly marked with a distinct rail-style line (e.g., dashed/ticked). Freight lines and MARTA heavy rail should look different from each other, because a freight line and a train station are very different things to live near. | R (distinction P) |
| MAP-5 | Street names are shown at zoom levels where they're legible. | R (implied by "every street") |
| MAP-6 | Zoom range: from whole-area view down to individual-house scale (~zoom 18). | P |
| MAP-7 | A **legend** and **layer toggles** (neighborhoods / high-traffic roads / railroads / my drawings / my markers) so I can declutter the map. | P |
| MAP-8 | Tapping a neighborhood (while in Pan mode) shows its name. | P |
| MAP-9 | Required data-source attribution (e.g., © OpenStreetMap contributors) is shown in an unobtrusive About/attribution spot. | P (license requirement) |

**Design intent:** The three feature layers (neighborhoods, traffic, rail) must read at a glance on a phone in daylight, and must stay legible when *my own highlighter colors* are drawn over them. The base map should therefore be muted/low-saturation.

## 4. Location

| ID | Requirement | Src |
|---|---|---|
| LOC-1 | Show my **current location** on the map as a dot (with accuracy circle), updating live. | R |
| LOC-2 | Request location permission on first use with a plain-language explanation; the app must still work fully (browse, draw, mark) if I deny it. | P |
| LOC-3 | A **"center on me"** button that recenters the map on my location. | P |
| LOC-4 | Location is used only while the app is open. No background tracking, and nothing is stored or transmitted. | P |

## 5. Navigation gestures

| ID | Requirement | Src |
|---|---|---|
| NAV-1 | **Pinch** to zoom in/out. | R |
| NAV-2 | **One-finger drag** to pan the map. | R |

### 5.1 Mode model (important; please review)

Your spec assigns one-finger drag to several jobs: pan, draw, erase, and move the marker dot. A single finger can only do one at a time, so the app has **exclusive modes** and a visible mode indicator.

| Mode | 1-finger drag | 2-finger pinch / drag | Tap |
|---|---|---|---|
| **Pan** (default) | Pan map | Zoom (and rotate, if enabled; see Q9) | Select neighborhood / marker |
| **Pen** | **Draw** highlight (snapped to street) | **Zoom + pan** | – |
| **Eraser** | **Erase** highlights under finger | **Zoom + pan** | Erase the highlight under the tap |
| **Mark** (placing) | **Move the dot** | **Zoom + pan** | – |

- MODE-1 [P]: Pinch-zoom must work in **every** mode. Two-finger pan should also work in every mode so I never have to leave Pen mode just to reposition the map.
- MODE-2 [P]: Exactly one mode is active. The active tool is highlighted in the toolbar. Tapping the active tool again returns to Pan.

## 6. Pen / highlighter

| ID | Requirement | Src |
|---|---|---|
| PEN-1 | A **Pen** tool with exactly **6 colors: red, orange, yellow, green, blue, purple**, selectable from a palette in the toolbar. | R |
| PEN-2 | Strokes are used to **highlight streets**. They render as semi-transparent, highlighter-style lines so the street name and features underneath remain visible. | R (style P) |
| PEN-3 | **Snap to street:** as I drag roughly along a street, the stroke snaps to that street's centerline. I should not need to be precise. | R |
| PEN-4 | Snapping should follow the street **along curves and through intersections** (i.e., it traces the actual road network), not just jump to the nearest point on each touch sample. If I drag along Peachtree, it stays on Peachtree unless I clearly turn off it. | P (defines "good" snapping) |
| PEN-5 | **Snap tolerance:** the stroke snaps to a street if my finger is within roughly 24–32 dp of it. If no street is within tolerance, the stroke is **not drawn**, or it's drawn freehand if the user enables a freehand toggle (see Q6). | P |
| PEN-6 | A **snap on/off toggle** so I can also draw freehand (e.g., to circle a whole area). | P (Q6) |
| PEN-7 | Highlights are **attached to the map** (stored as geographic coordinates), so they stay glued to the street when I pan or zoom, and keep a readable width at every zoom level. | P (required for this to work) |
| PEN-8 | **Undo / redo** for the last drawing or erase action. | P |
| PEN-9 | Drawing must feel immediate: the highlight follows the finger with no perceptible lag, and the snap result resolves within one frame (~16 ms) per touch sample on a mid-range device. | P |

## 7. Eraser

| ID | Requirement | Src |
|---|---|---|
| ERS-1 | An **Eraser** tool that is one tap away in the main toolbar (never buried in a menu). | R |
| ERS-2 | Dragging a finger over a highlight **removes that highlight** where the finger passes. Because highlights are made of street segments, erasing removes whole street segments (block-by-block) rather than fiddly pixel fragments. | R (granularity P; see Q7) |
| ERS-3 | Tapping directly on a highlight erases it. | P |
| ERS-4 | Eraser has a generous touch radius (~24–32 dp) so I don't need precision. | P |
| ERS-5 | Erasing is undoable (PEN-8). | P |
| ERS-6 | A **"Clear all drawings"** action, protected by a confirmation dialog. | P |
| ERS-7 | The eraser only removes **drawings**. It never touches markers or notes. | P |

## 8. Markers and notes

### 8.1 Placement flow [R]

1. I tap the **Mark** button. A small **dot** appears with a **vertical dotted line** and a **horizontal dotted line** running through it (a crosshair spanning the screen).
2. I **drag one finger** to move the dot around. (Pinch still zooms the map.)
3. I tap the **Mark** button **again** to place the marker at the dot's location.
4. A **notepad pops up** immediately for that marker.
5. I **tap inside the notepad**. The keyboard appears and I can type/edit.
6. I tap a **single Save button**. The note is saved to that marker and the notepad closes.

### 8.2 Requirements

| ID | Requirement | Src |
|---|---|---|
| MRK-1 | Flow in §8.1 exactly as described. | R |
| MRK-2 | The crosshair lines are **dotted**, extend to the screen edges, and have enough contrast to see on any part of the map. The dot is large enough to see under a fingertip: either it is drawn offset above the finger, or a small readout of the target position is shown (see Q8). | R (visibility P) |
| MRK-3 | Placed markers are shown as a **pin/dot icon on the map** at the chosen geographic location and stay attached as I pan and zoom. | R (implied) |
| MRK-4 | **Cancel:** I can back out of Mark mode without placing anything (a Cancel button, or tap another tool). | P |
| MRK-5 | **Reopen a note:** tapping an existing marker opens its notepad. Editing follows the same tap → keyboard → Save flow. | P (a note you can't reopen isn't useful) |
| MRK-6 | **Delete a marker** (and its note), with confirmation. | P |
| MRK-7 | **Move a placed marker** (e.g., long-press then drag). | P (nice-to-have; low priority) |
| MRK-8 | Notepad text is plain text, with no length limit that I'd plausibly hit (≥ 5,000 characters). The keyboard must not cover the note or the Save button. | P |
| MRK-9 | If I dismiss the notepad without saving after edits, ask before discarding. If it's a brand-new empty marker, keep the marker with an empty note. | P (Q10) |
| MRK-10 | Markers show a short note preview (first line) as a label when zoomed in enough. | P |

## 9. Decisions (formerly open questions)

**All defaults below were approved on 2026-09-20.** The one addition is Q13: the target phone runs **Android 17**, so we build for SDK 35 (installed) and test on an Android 15 emulator before the phone. The minimum-SDK floor in Q13 stays as written, and it can be raised later if it simplifies anything.

| # | Question | My default / recommendation |
|---|---|---|
| Q1 | **Which map source?** Google Maps SDK gives familiar visuals, but styling is limited and usage is metered. An open-source stack (MapLibre + OpenStreetMap data) gives full control over how neighborhoods, high-traffic roads, and rails look, needs no API key, and can be **fully offline**. | **MapLibre + OSM**, with the Atlanta region packaged as an offline tile file. |
| Q2 | **Offline?** Should the app work with no signal? | Yes. Base map is stored on the device (estimated tens to low hundreds of MB). |
| Q3 | **Neighborhood boundary data.** The City of Atlanta publishes official neighborhood boundaries, but that only covers the city itself. Sandy Springs, Decatur, Brookhaven, Dunwoody, etc. each publish their own (or use OSM's less official ones). | Atlanta official data + each other city's GIS data or OSM where none exists. Boundaries are a mix of official and informal, and I'll flag which is which. **Decided 2026-09-20 after research:** outside Atlanta the official data is subdivision-scale (Sandy Springs 415, Brookhaven 166; median ~11 acres), Chamblee's is unusable, and other cities publish nothing. So: Atlanta keeps its 248 official boundaries; Sandy Springs' 12 civic-association areas get the same look; Sandy Springs and Brookhaven subdivisions show only when zoomed in close; OpenStreetMap name-only labels (315, muted color) fill in elsewhere. |
| Q4 | **What counts as "high traffic"?** (a) Road *class* (interstates, state routes, major arterials), or (b) measured traffic volume (GDOT publishes annual average daily traffic counts). | (a) for v1, using road class. (b) as an upgrade if you want a real number, e.g., "> 25,000 cars/day". |
| Q5 | **Which railroads?** Active freight (Norfolk Southern / CSX), MARTA rail, and the BeltLine (a former rail corridor now used as a trail) are all "rail-ish". | Show freight and MARTA as two distinct styles. Show the BeltLine as a trail, not a railroad. |
| Q6 | **Drawing outside a street:** if my finger is nowhere near a street, should the pen draw nothing, or draw freehand? | Draw nothing while snap is on. Add a snap/freehand toggle (PEN-6). |
| Q7 | **Eraser granularity:** erase whole highlighted streets, or block-by-block segments? | Segment-by-segment (between intersections), plus Undo. |
| Q8 | **Marker dot behavior:** (a) the dot itself moves under your finger over the map, or (b) the dot stays fixed at screen center and the map slides underneath (like Google Maps' pin drop). Your description reads like (a). | **(a)**, with the dot offset above the fingertip so it isn't hidden. |
| Q9 | **Map rotation** (two-finger twist)? Handy for aligning with streets in the field, but easy to trigger by accident. | **Off** (north always up), with a setting to enable. |
| Q10 | **Persistence.** Should drawings and notes survive closing the app? | Yes. Stored locally on the device, with no account. |
| Q11 | **Backup/export?** Losing weeks of notes to a phone swap would hurt. | Manual export/import of drawings + notes to a file (v1.1). |
| Q12 | **Address/place search?** (e.g., type "Inman Park" and jump there.) | Include a basic search box for neighborhoods and street names (P; low priority). |
| Q13 | **Orientation, theme, device.** Portrait only? Dark mode? Which Android version does your phone run? | Portrait + landscape supported, follow system theme, min Android 8 (API 26). **Decided:** phone runs Android 17, compile against the installed SDK 35 (apps built for 35 run on Android 17). Emulator is Android 15. |
| Q14 | **Distribution.** Personal use only, or Play Store later? | Personal: installed directly (sideloaded) from Android Studio / an APK. |
| Q15 | **Toolbar layout.** | Bottom toolbar (thumb reach): Pan · Pen (with color palette) · Eraser · Mark · My Location. Undo/redo and layers up top. |

## 10. Non-functional requirements

| ID | Requirement |
|---|---|
| NFR-1 | Panning and zooming stay smooth (target 60 fps) on a mid-range phone with all layers and a few hundred highlights/markers visible. |
| NFR-2 | Cold start to interactive map in ≤ 3 seconds (offline map). |
| NFR-3 | All user data (drawings, markers, notes) is stored **locally**; nothing leaves the device. No account, no analytics in v1. |
| NFR-4 | Saved data is written immediately (or on every Save/stroke), so a crash or swipe-away doesn't lose work. |
| NFR-5 | Touch targets ≥ 48 dp. Colors chosen so red/green highlights remain distinguishable for color-blind users (e.g., via distinct lightness or an outline). |
| NFR-6 | Works with no network after the initial install. |
| NFR-7 | Map data license terms (OSM ODbL, city open-data licenses) are followed, with attribution in-app. |

## 11. Proposed technical approach (for discussion, not a requirement)

- **App:** Native Android, **Kotlin + Jetpack Compose**.
- **Map:** **MapLibre Native** with a custom-styled vector basemap built from OpenStreetMap for the coverage polygon (e.g., via Planetiler → PMTiles/MBTiles), stored on the device. This gives full control over neighborhood, high-traffic-road and railroad styling, and offline use.
- **Snap-to-street:** build a compact **road graph** of the coverage area (nodes = intersections, edges = street segments). On each touch sample, find the nearest edge within tolerance, then connect successive snap points by **shortest path along the graph**. That's what makes the stroke follow curves rather than cutting corners.
- **Drawings:** store each stroke as a list of street-segment IDs and/or coordinates, in a local **Room/SQLite** database. Render them as a MapLibre line layer, so they stay attached to the map.
- **Markers/notes:** Room table (id, lat, lng, note, timestamps), rendered as a MapLibre symbol layer.
- **Location:** Android **Fused Location Provider**, foreground only.
- **Data sources:** OpenStreetMap (roads, rail, base), City of Atlanta Open Data (neighborhood boundaries), other cities' GIS portals, and optionally GDOT traffic counts.

## 12. Suggested build order

1. **Map foundation:** offline basemap, pan/zoom, my location. *(proves the data pipeline, the riskiest part)*
2. **Feature layers:** neighborhoods, high-traffic roads, railroads, legend/toggles.
3. **Markers & notes:** placement flow, notepad, save, reopen, delete.
4. **Pen (unsnapped first), eraser, undo/redo,** and persistence.
5. **Snap-to-street:** road graph and shortest-path snapping.
6. Polish: search, export/backup, accessibility, performance pass.

## 13. Possible future features (not in v1)

Layers for school ratings, crime, home prices, and noise (flight paths, freight rail); commute-time checker from a chosen address; neighborhood scorecards using my notes; share/sync with a partner; iOS version.

## 14. Where things stand (2026-09-21)

| Area | Status |
|---|---|
| **Map, neighborhoods, high-traffic roads, railroads** (MAP-1 to MAP-9) | Done. Rail now separates freight (CSX, Norfolk Southern) from MARTA rail, with yards and spurs faint and all 37 MARTA stations named. Layers panel with legend and switches (saved between launches), tap a neighborhood for its name, "About & data sources" for attribution. |
| **My location** (LOC-1, LOC-3, LOC-4) | Done. LOC-2: uses Android's standard permission prompt, with no custom explanation screen. Denying it is handled in code but has not been tested on a device. |
| **Pinch and drag** (NAV-1, NAV-2, MODE-1, MODE-2) | Done and confirmed on the phone. |
| **Pen** (PEN-1 to PEN-9) | Done: six colors, snap to street (with an on/off switch), undo/redo, saved on the phone. |
| **Eraser** (ERS-1 to ERS-7) | Done, with one change from the plan: it erases like a real eraser (cuts a highlight apart where your finger passes) instead of whole blocks. |
| **Markers and notes** (MRK-1 to MRK-6, MRK-8 to MRK-10) | Done and confirmed on the phone. |
| **Backup and restore** (Q11) | Done. Layers → Back up & restore: Export saves markers, notes and highlights as one small readable file (you choose where: Drive, Downloads, email). Import reads it back, either adding to what's on the phone (matched by id; the most recently edited note wins) or replacing it (asks first). Damaged or foreign files are rejected without changing anything. Also set up: Android's automatic cloud backup for just those files (untested, since it needs a real backup account). |
| **Search** (Q12) | Done, fully offline. A Search button beside Layers finds neighborhoods, subdivisions, cities, MARTA stations and all ~25,000 named streets (each street labeled with the neighborhood it's in, so two "Oak Drive"s can be told apart), plus your own notes by their text. Handles abbreviations ("n highland ave ne"), any word order, accents, half-typed words. Picking a result flies the map there and rings it for a few seconds. **Limit:** street names only, no house numbers (OpenStreetMap has none for most of Atlanta). |
| **Drawing layers** (added 2026-09-21) | Done. Up to 4 named drawing layers, each with its own on/off switch (Layers panel → My drawing layers). Tap a layer to rename it, make it the one you draw on, or delete it (deleting removes its highlights and can't be undone). In Pen mode a "Draw on" row of chips switches the active layer. The eraser and Clear all only touch layers that are showing. Highlights you drew before this existed are on "Layer 1". Layers are included in backups (older backups still import). Markers stay a single layer. |
| **Navigate to here** (added 2026-09-21) | Done. A link in each marker's notepad opens Google Maps with driving directions from your current location; falls back to the browser version if Google Maps isn't installed. |
| **School boundaries** (requested 2026-09-21) | Done, boundaries only (no ratings: GreatSchools' terms forbid bundling them). Layers → Elementary school zones (off by default) shows colored, named zones; tap the map to see "Elementary zone: Lin ES (APS)". **Sources:** DeKalb County School District and Fulton County Schools (each district's own published zones, 2026); Atlanta Public Schools from a third-party compilation (Aug 2026), because APS's own published layer is the 2006-07 map. **City Schools of Decatur** (added later the same day): traced from the district's official 2025-26 boundary PDF (5 K-2 zones drawn filled, 2 upper-elementary 3-5 zones drawn as dashed outlines; tapping shows both). Placement was checked against Decatur's district outline (99.0% overlap) and against about 300 street-name labels on the page (median 2 m off, 90% within 5 m). **Cobb County School District** (added 2026-09-21): traced from the district's countywide 2023-24 elementary zone map (a PDF on cobbk12.org), which is older than the other sources. Placement was checked against real roads (median 7.5 m; two halves of the roads agree within 5 m everywhere in the map area) and against Cobb County's official outline (98.9% overlap, edge a median 40 m from the county line); the zones don't overlap any neighboring district's. Each zone is named after the school building inside it (all but one also by the name printed on the page). **Marietta City Schools** (added 2026-09-21; a separate district that isn't on Cobb's map): built from the City of Marietta's public parcel layer, where every parcel records its elementary school (edited Sept 2026), by grouping parcels by that code. All seven schools' buildings sit inside their own zone. Cross-checked against the Marietta-shaped hole in Cobb's PDF: they agree except for about 1 km² at the far west edge, and everything the PDF shows that the parcels don't is thin slivers (streets). The city says users must verify its GIS data. **Gaps:** the airport/Clayton and Gwinnett edges are uncovered. About 93% of the map area is covered. **Caveats:** zones change, and APS's own account has two scenario layers edited 2026-08-24, which suggests rezoning is being studied. Confirm any school with the district before relying on it. |
| **Not built** | MRK-7 (drag a placed marker to a new spot), dark theme (Q13), color-blind-safe outlines on highlights (NFR-5). |

## 15. Possible future directions (optional, not scheduled)

Written down on 2026-09-21 so they aren't forgotten. Nothing here is planned or promised; each needs a go-ahead and some scoping first.

### 15.1 iOS, and testing on tablets

- **Goal:** run House Hunter on iPhone and iPad, and confirm it works well on Android tablets too.
- **What carries over as is:** all the data files (basemap, neighborhoods, school zones, rail, road graph, search index) and `style.json`. MapLibre, the map engine, has an iOS version that reads the same files. The data-building scripts in `data/` don't depend on Android.
- **What would have to be written again for iOS:** the app itself (screens, gestures, pen, eraser, markers, layers panel, backup, search) is Kotlin and Jetpack Compose, which don't run on iOS. Options to weigh: a native Swift/SwiftUI app; or moving the shared logic (snap-to-street matcher, eraser geometry, search, backup format and merge rules, and their tests) into Kotlin Multiplatform so both apps use it. The logic is already separate from the screens, which makes either route more practical.
- **Android-only pieces to replace:** location (Apple's location framework), the file picker for backup, cloud auto-backup, and "Navigate to here" (open Apple Maps, or Google Maps if installed).
- **Tablets:** adaptive layouts (wider screens, landscape, a side panel for Layers and notes instead of full-width sheets), larger touch and drawing areas, and testing on tablet-size emulators or real tablets. A stylus would be a natural fit for the pen, so it's worth deciding whether to treat a stylus differently from a finger (for example, drawing without needing a tool selected).
- **Also needed:** an Apple developer account and a Mac for builds, plus real iPhone and iPad testing, since the iOS simulator can't do everything a device can.
- **Open question:** whether it's worth maintaining two apps for a personal tool.

### 15.2 Expand to other major cities

- **Goal:** the same map, layers, drawing and notes for other metro areas (for example, another city you might move to).
- **Already generalized:** the map tiles come from a public worldwide OpenStreetMap build, so a new region is a new bounding box. The road graph, rail, and search index are built from an OpenStreetMap extract by scripts that only need a new area and extract.
- **What is Atlanta-specific today and would need generalizing:** the coverage box and starting view are constants in the app; the local distance math is tuned to Atlanta's latitude (a small change to use the right latitude per city); the road, search, and school-zone files are bundled as a single region; and the city names, "I-285" framing and data-source notes appear in text.
- **What needs new research per city (the bulk of the work):** neighborhood boundaries (Atlanta had an official city layer; other cities vary, and some have none); school zones (each district publishes differently, and here they came from five separate sources, two of them traced from PDFs); high-traffic road definitions; and a definition of the area to cover.
- **App structure to add:** a city picker, and either downloading each city's data on demand or shipping one app per city, since the Atlanta data alone is about 100 MB. Saved notes and drawings would need to be kept per city, and backup files should record which city they belong to.
- **Suggested first step if pursued:** pick one second city, list its data sources, and check that neighborhoods and school zones are available before building anything.

### 15.3 Swim/tennis community markers

- **Goal:** a small star (or similar icon) on the map marking neighborhoods with a swim/tennis club, since that's a common amenity in Atlanta-area subdivisions and a factor in choosing where to live.
- **Data:** no single source found yet. OpenStreetMap tags some pools and tennis courts individually (`leisure=swimming_pool`, `leisure=pitch` + `sport=tennis`), but not the "swim/tennis community" bundle as a concept, and coverage is inconsistent (many HOA-only pool/tennis clubs aren't mapped at all). Likely needs some combination of: querying OSM for clusters of a pool + tennis courts + clubhouse close together, cross-checking against neighborhood/subdivision names that include "swim" or "tennis" or "recreation," and manual spot-checking. Could start crowdsourced (mark them yourself from the Mark tool, on their own layer) rather than an automated data pass.
- **App side:** a new marker style (star icon, its own color) and its own layer in the Layers panel, similar to how school zones got a toggle.
- **Suggested first step if pursued:** check how many swim/tennis clubs OpenStreetMap actually has tagged inside the coverage area, to see whether an automated pass is worth it or whether this starts as a manually-marked layer.

