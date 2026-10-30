package com.househunter.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.hypot

// Coverage area from requirements.md §2.1 (inside I-285 plus Sandy Springs).
private val COVERAGE = LatLngBounds.from(34.02, -84.20, 33.60, -84.60)
private val ATLANTA_CENTER = LatLng(33.81, -84.40)

/** What one finger does on the map (requirements §5.1). Pinch-zoom and two-finger pan work in every tool. */
enum class Tool { Pan, Pen, Eraser, Mark }

/** Per-gesture scratch state for the touch handler; not UI state, so it lives outside Compose's snapshot system. */
private class TouchState {
    val start = PointF()
    val last = PointF()
    var multiTouch = false
    var penStarted = false
    val penPx = ArrayList<PathSimplifier.Pt>()
    val penPoints = ArrayList<LatLng>()
    /** Non-null while a stroke is being snapped to streets. */
    var matcher: SnapMatcher? = null
    var snapRadius = 30f
    /** The raw finger trace in meters, for the whole-stroke pass that runs when the finger lifts. */
    val penLocal = ArrayList<Pair<Float, Float>>()
    var drawnVersion = -1
}

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(startCamera: CameraPosition? = null, vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var locationReady by remember { mutableStateOf(false) }
    var styleReady by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    var tool by remember { mutableStateOf(Tool.Pan) }
    val prefs = remember { AppPrefs(context) }
    var penColor by remember { mutableStateOf(prefs.penColor) }
    var snap by remember { mutableStateOf(prefs.snap) }
    var hidden by remember { mutableStateOf(prefs.hiddenLayers) }
    var hiddenDrawing by remember { mutableStateOf(prefs.hiddenDrawingLayers) }
    var editingLayerId by remember { mutableStateOf<String?>(null) }
    var showLayers by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var tapInfo by remember { mutableStateOf<String?>(null) }
    var showBackup by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var resultPin by remember { mutableStateOf<LatLng?>(null) }
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    var pendingImport by remember { mutableStateOf<Backup?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var dot by remember { mutableStateOf(Offset.Zero) }
    var eraserAt by remember { mutableStateOf<Offset?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var openNoteId by remember { mutableStateOf<String?>(null) }
    var noteHeightPx by remember { mutableStateOf(0) }
    val toolNow by rememberUpdatedState(tool)
    val penColorNow by rememberUpdatedState(penColor)
    val openNoteNow by rememberUpdatedState(openNoteId)
    val loadedNow by rememberUpdatedState(vm.loaded)
    val snapNow by rememberUpdatedState(snap)
    val hiddenDrawingNow by rememberUpdatedState(hiddenDrawing)
    val roadsNow by rememberUpdatedState(vm.roads)
    val mapNow by rememberUpdatedState(map)
    val touch = remember { TouchState() }

    val eraserRadiusPx = 26 * density
    val penSlopPx = 8 * density

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun enableLocation(m: MapLibreMap) {
        val style = m.style ?: return
        if (!hasLocationPermission()) return
        m.locationComponent.apply {
            if (!isLocationComponentActivated) {
                activateLocationComponent(
                    LocationComponentActivationOptions.builder(context, style)
                        .useDefaultLocationEngine(true)
                        // GPS-grade fixes: the dot should follow me while I walk a neighborhood.
                        .locationEngineRequest(
                            LocationEngineRequest.Builder(1000)
                                .setFastestInterval(500)
                                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                                .build()
                        )
                        .build()
                )
            }
            isLocationComponentEnabled = true
            renderMode = RenderMode.NORMAL
            cameraMode = CameraMode.NONE
        }
        locationReady = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map?.let(::enableLocation) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        // The lifecycle is already past ON_CREATE when this composes.
        mapView.onCreate(null)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Copying the bundled map files can take a moment on first launch, so keep it off the main thread.
    var styleJson by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        styleJson = withContext(Dispatchers.IO) { MapAssets.styleJson(context) }
    }
    LaunchedEffect(map, styleJson) {
        val m = map ?: return@LaunchedEffect
        val json = styleJson ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(json)) { style ->
            // Highlights go under the labels; markers go on top of everything.
            HighlightLayer.install(style)
            MarkerLayer.install(style, density)
            MarkerLayer.installSearchResult(style)
            styleReady = true
            m.setMinZoomPreference(9.5)
            m.setMaxZoomPreference(19.0)
            m.setLatLngBoundsForCameraTarget(COVERAGE)
            m.cameraPosition = startCamera ?: CameraPosition.Builder().target(ATLANTA_CENTER).zoom(10.8).build()
            // North stays up (requirements Q9): no rotate or tilt, no compass.
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.uiSettings.isCompassEnabled = false
            if (hasLocationPermission()) {
                enableLocation(m)
            } else {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        }
    }

    val markerSnapshot = vm.markers.toList()
    LaunchedEffect(styleReady, markerSnapshot) {
        if (styleReady) map?.style?.let { MarkerLayer.update(it, markerSnapshot) }
    }

    // The saved highlights. The stroke being drawn is cleared here, in the same step the saved copy appears,
    // so a finished stroke never flickers out for a frame.
    val strokeSnapshot = vm.strokes
    LaunchedEffect(styleReady, strokeSnapshot, hiddenDrawing) {
        val style = map?.style?.takeIf { styleReady } ?: return@LaunchedEffect
        HighlightLayer.update(style, strokeSnapshot.filter { it.layerId !in hiddenDrawing })
        if (!touch.penStarted) HighlightLayer.updateDraft(style, emptyList(), penColorNow)
    }

    LaunchedEffect(styleReady, hidden) {
        if (styleReady) map?.style?.let { MapLayers.apply(it, hidden) }
    }
    LaunchedEffect(resultPin, styleReady) {
        val style = map?.style?.takeIf { styleReady } ?: return@LaunchedEffect
        val pin = resultPin
        if (pin == null) {
            MarkerLayer.clearSearchResult(style)
        } else {
            MarkerLayer.showSearchResult(style, pin.latitude, pin.longitude)
            delay(8000)
            resultPin = null
        }
    }
    LaunchedEffect(tapInfo) {
        if (tapInfo != null) {
            delay(3500)
            tapInfo = null
        }
    }

    // Drawing or marking while that layer is hidden would be baffling, so choosing the tool reveals it.
    fun reveal(group: LayerGroup) {
        if (group in hidden) {
            hidden = hidden - group
            prefs.hiddenLayers = hidden
        }
    }

    fun setDrawingLayerShown(id: String, shown: Boolean) {
        hiddenDrawing = if (shown) hiddenDrawing - id else hiddenDrawing + id
        prefs.hiddenDrawingLayers = hiddenDrawing
    }

    /** Drawing on a layer that is switched off would be baffling, so choosing to draw on it turns it on. */
    fun selectDrawingLayer(id: String) {
        vm.setActiveLayer(id)
        if (id in hiddenDrawing) setDrawingLayerShown(id, true)
    }

    // With a drawing tool active, one finger belongs to the tool, so the map's own one-finger drag is off.
    LaunchedEffect(map, tool) { map?.uiSettings?.isScrollGesturesEnabled = tool == Tool.Pan }

    // Tapping a placed marker reopens its note (MRK-5). Only in Pan mode, and not while a note is open.
    LaunchedEffect(map) {
        val m = map ?: return@LaunchedEffect
        m.addOnMapClickListener { latLng ->
            if (toolNow != Tool.Pan) return@addOnMapClickListener true
            if (openNoteNow != null) return@addOnMapClickListener false
            val point = m.projection.toScreenLocation(latLng)
            val id = MarkerLayer.idAt(m, point, 24 * density)
            if (id != null) {
                openNoteId = id
                return@addOnMapClickListener true
            }
            // Otherwise: which neighborhood did I tap? (MAP-8)
            tapInfo = neighborhoodAt(m, point)
            tapInfo != null
        }
    }

    // Bring the marker being edited into view, above the notepad.
    LaunchedEffect(openNoteId) {
        val marker = vm.markers.firstOrNull { it.id == openNoteId } ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val zoom = maxOf(m.cameraPosition.zoom, 15.0)
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(marker.lat, marker.lon), zoom), 350)
    }

    // While a note is open the notepad (and keyboard) covers the bottom of the screen. Scroll the map so the
    // marker being edited sits in the visible strip above it. Re-runs when the keyboard opens or closes.
    val insets = LocalDensity.current
    val bottomInset = maxOf(WindowInsets.ime.getBottom(insets), WindowInsets.navigationBars.getBottom(insets))
    val coveredByNote =
        if (openNoteId != null && noteHeightPx > 0) bottomInset + noteHeightPx + (12 * density).toInt() else 0
    LaunchedEffect(openNoteId, coveredByNote) {
        val marker = vm.markers.firstOrNull { it.id == openNoteId } ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (coveredByNote == 0) return@LaunchedEffect
        delay(450) // let the zoom-in animation from opening the note finish first
        val pin = m.projection.toScreenLocation(LatLng(marker.lat, marker.lon))
        val visibleCentre = (viewSize.height - coveredByNote) / 2f
        // Look at whatever is currently drawn that far below the pin's row, which slides the pin up into view.
        val lookAt = m.projection.fromScreenLocation(PointF(pin.x, viewSize.height / 2f + (pin.y - visibleCentre)))
        m.animateCamera(CameraUpdateFactory.newLatLng(lookAt), 250)
    }


    // ---- Backup and restore (Q11): the phone's own file picker, so no storage permission is needed ----------------

    fun say(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun counts(markers: Int, strokes: Int) =
        "${if (markers == 1) "1 marker" else "$markers markers"} and ${if (strokes == 1) "1 highlight" else "$strokes highlights"}"

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val backup = vm.snapshotBackup()
            scope.launch {
                val saved = runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8)) }
                    }
                }
                if (saved.isSuccess) {
                    prefs.lastBackupAt = backup.exportedAt
                    say("Saved ${counts(backup.markers.size, backup.strokes.size)}.")
                } else {
                    say("Couldn't save the backup. Try a different location.")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val read = runCatching {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        if (bytes.size > 50_000_000) throw BackupFormatException("That file is too big to be a House Hunter backup.")
                        BackupCodec.decode(String(bytes, Charsets.UTF_8))
                    }
                }
                read.onSuccess { pendingImport = it }
                    .onFailure { say((it as? BackupFormatException)?.message ?: "Couldn't read that file.") }
            }
        }
    }

    /** Opens Google Maps with turn-by-turn directions from wherever I am to this spot. */
    fun navigateTo(lat: Double, lon: Double) {
        val place = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$place&mode=d"))
            .setPackage("com.google.android.apps.maps")
        try {
            context.startActivity(maps)
        } catch (e: ActivityNotFoundException) {
            // No Google Maps: the same directions link opens in a browser or any other maps app.
            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$place&travelmode=driving"))
            try {
                context.startActivity(web)
            } catch (e2: ActivityNotFoundException) {
                say("No maps app or browser found to start directions.")
            }
        }
    }

    fun finishImport(backup: Backup, replace: Boolean) {
        pendingImport = null
        confirmReplace = false
        val result = vm.importBackup(backup, replace)
        say(
            when {
                result == null -> "Still starting up. Try again in a moment."
                replace -> "Replaced with ${counts(backup.markers.size, backup.strokes.size)}."
                result.addedMarkers + result.updatedMarkers + result.addedStrokes == 0 -> "Nothing new in that file; everything was already here."
                else -> "Added ${counts(result.addedMarkers, result.addedStrokes)}" +
                    if (result.updatedMarkers > 0) " and updated ${result.updatedMarkers} note${if (result.updatedMarkers == 1) "" else "s"}." else "."
            }
        )
    }

    // ---- Search: pick a result and the map flies there ---------------------------------------------------------

    fun flyTo(entry: SearchEntry) {
        val m = map ?: return
        val diagonal = hypot((entry.east - entry.west) * 92_400, (entry.north - entry.south) * 110_540)
        val update = when {
            entry.type == SearchType.CITY -> CameraUpdateFactory.newLatLngZoom(LatLng(entry.lat, entry.lon), 13.0)
            entry.type == SearchType.STATION -> CameraUpdateFactory.newLatLngZoom(LatLng(entry.lat, entry.lon), 16.0)
            diagonal < 300 -> CameraUpdateFactory.newLatLngZoom(LatLng(entry.lat, entry.lon), 16.5)
            // A street or area too long to show whole (or two far-apart stretches): show its middle, not a blur.
            diagonal > 12_000 -> CameraUpdateFactory.newLatLngZoom(LatLng(entry.lat, entry.lon), 12.5)
            else -> CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(entry.north, entry.east, entry.south, entry.west),
                (48 * density).toInt(), (140 * density).toInt(), (48 * density).toInt(), (240 * density).toInt(),
            )
        }
        m.animateCamera(update, 900)
        resultPin = LatLng(entry.lat, entry.lon)
        tapInfo = "${entry.name} · ${entry.subtitle}"
    }

    fun closeSearch() {
        searching = false
        query = ""
        focusManager.clearFocus()
    }

    fun pick(hit: SearchHit) {
        closeSearch()
        if (hit.noteId != null) openNoteId = hit.noteId else hit.entry?.let(::flyTo)
    }

    BackHandler(enabled = searching) { closeSearch() }
    val searchIndex = vm.searchIndex
    val hits = remember(query, searchIndex, markerSnapshot) { buildSearchHits(query, searchIndex, markerSnapshot) }

    BackHandler(enabled = tool != Tool.Pan) { tool = Tool.Pan }

    fun onMarkPressed() {
        val m = map ?: return
        if (!vm.loaded) return
        if (tool != Tool.Mark) {
            reveal(LayerGroup.Markers)
            dot = Offset(viewSize.width / 2f, viewSize.height / 2f)
            tool = Tool.Mark
        } else {
            val at = m.projection.fromScreenLocation(PointF(dot.x, dot.y))
            openNoteId = vm.add(at.latitude, at.longitude).id
            tool = Tool.Pan
        }
    }

    // ---- Touch handling for the drawing tools --------------------------------------------------------------
    // The handler returns false so the map still sees every touch; the map's own one-finger drag is switched
    // off while a tool is active, so only pinch and two-finger pan get through.

    fun screenToLatLon(x: Float, y: Float): LatLng? = mapNow?.projection?.fromScreenLocation(PointF(x, y))

    fun localToLatLng(p: Pair<Float, Float>) = LatLng(LocalFrame.lat(p.second.toDouble()), LocalFrame.lon(p.first.toDouble()))

    fun refreshDraft() {
        val style = mapNow?.style ?: return
        val matcher = touch.matcher
        val parts = if (matcher != null) {
            touch.drawnVersion = matcher.version
            matcher.polylines().map { line -> line.map(::localToLatLng) }
        } else {
            listOf<List<LatLng>>(touch.penPoints)
        }
        HighlightLayer.updateDraft(style, parts, penColorNow)
    }

    fun addPenPoint(x: Float, y: Float) {
        val at = screenToLatLon(x, y) ?: return
        touch.penPx.add(PathSimplifier.Pt(x.toDouble(), y.toDouble()))
        touch.penPoints.add(at)
        val local = LocalFrame.x(at.longitude) to LocalFrame.y(at.latitude)
        touch.penLocal.add(local)
        touch.matcher?.add(local.first, local.second)
    }

    /** How close to a street the finger must be to snap: the same 28 dp on screen at any zoom. */
    fun snapRadiusMeters(x: Float, y: Float): Float {
        val a = screenToLatLon(x, y) ?: return 30f
        val b = screenToLatLon(x + 28 * density, y) ?: return 30f
        return a.distanceTo(b).toFloat().coerceIn(6f, 250f)
    }

    fun penSample(x: Float, y: Float) {
        if (!touch.penStarted) {
            // Ignore the first few pixels of movement so a two-finger pinch that starts with one finger
            // doesn't leave a stray dot.
            if (hypot(x - touch.start.x, y - touch.start.y) > penSlopPx) {
                touch.penStarted = true
                touch.drawnVersion = -1
                touch.snapRadius = snapRadiusMeters(x, y)
                touch.matcher = roadsNow?.takeIf { snapNow }?.let { SnapMatcher(it, touch.snapRadius) }
                addPenPoint(touch.start.x, touch.start.y)
                addPenPoint(x, y)
                touch.last.set(x, y)
            }
        } else if (hypot(x - touch.last.x, y - touch.last.y) >= 2 * density) {
            addPenPoint(x, y)
            touch.last.set(x, y)
        }
    }

    fun finishPen(commit: Boolean) {
        var committed = false
        if (commit && touch.penStarted) {
            val matcher = touch.matcher
            val parts: List<List<LatLon>> = if (matcher != null) {
                // Snapped: whatever streets the finger traced. While drawing, points were matched one at a time;
                // now that the whole trace is known, match it again with hindsight (it drops wobbles onto side
                // streets). A stroke that never came near a street saves nothing.
                val refined = roadsNow?.let { g -> SnapMatcher(g, touch.snapRadius).also { it.matchAll(touch.penLocal) } }
                (refined?.takeUnless { it.isEmpty } ?: matcher).polylines().map { line -> line.map { LatLon(LocalFrame.lat(it.second.toDouble()), LocalFrame.lon(it.first.toDouble())) } }
            } else if (touch.penPx.size >= 2) {
                val kept = PathSimplifier.simplify(touch.penPx, 1.5)
                listOf(kept.mapNotNull { p -> screenToLatLon(p.x.toFloat(), p.y.toFloat())?.let { LatLon(it.latitude, it.longitude) } })
            } else emptyList()
            val real = parts.filter { it.size >= 2 }
            if (real.isNotEmpty()) {
                touch.penStarted = false // so the highlights effect clears the draft along with adding the saved copy
                vm.commitStroke(Stroke(color = penColorNow, parts = real, layerId = vm.activeLayerId))
                committed = true
            }
        }
        touch.penStarted = false
        touch.matcher = null
        touch.penPx.clear()
        touch.penPoints.clear()
        touch.penLocal.clear()
        if (!committed) refreshDraft() // nothing was saved, so remove the draft line ourselves
    }

    fun eraseAt(x: Float, y: Float) {
        val m = mapNow ?: return
        val center = m.projection.fromScreenLocation(PointF(x, y))
        val edge = m.projection.fromScreenLocation(PointF(x + eraserRadiusPx, y))
        vm.erase(LatLon(center.latitude, center.longitude), center.distanceTo(edge), hiddenDrawingNow)
    }

    fun eraserSample(x: Float, y: Float) {
        // Sweep from the last position so a fast drag can't skip over a stroke.
        val dx = x - touch.last.x
        val dy = y - touch.last.y
        val steps = ceil(hypot(dx, dy) / (eraserRadiusPx / 2)).toInt().coerceAtLeast(1)
        for (i in 1..steps) eraseAt(touch.last.x + dx * i / steps, touch.last.y + dy * i / steps)
        touch.last.set(x, y)
        eraserAt = Offset(x, y)
    }

    fun endGesture(commit: Boolean) {
        when (toolNow) {
            Tool.Pen -> finishPen(commit)
            Tool.Eraser -> { vm.endErase(); eraserAt = null }
            else -> {}
        }
    }

    fun onMapTouch(ev: MotionEvent) {
        val active = toolNow
        if (active == Tool.Pan) return
        val m = mapNow ?: return
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touch.multiTouch = false
                touch.start.set(ev.x, ev.y)
                touch.last.set(ev.x, ev.y)
                if (active == Tool.Eraser && loadedNow) {
                    vm.beginErase()
                    eraseAt(ev.x, ev.y) // a tap on a highlight erases it (ERS-3)
                    eraserAt = Offset(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A second finger means pinch/pan: end whatever the first finger was doing, hand over to the map.
                if (!touch.multiTouch) endGesture(commit = true)
                touch.multiTouch = true
                m.uiSettings.isScrollGesturesEnabled = true
            }
            MotionEvent.ACTION_POINTER_UP -> if (ev.pointerCount <= 2) m.uiSettings.isScrollGesturesEnabled = false
            MotionEvent.ACTION_MOVE -> if (!touch.multiTouch && ev.pointerCount == 1 && loadedNow) {
                fun sample(x: Float, y: Float) = when (active) {
                    Tool.Pen -> penSample(x, y)
                    Tool.Eraser -> eraserSample(x, y)
                    Tool.Mark -> {
                        // Drag by however far the finger moves, so the finger never covers the dot.
                        dot = Offset(
                            (dot.x + x - touch.last.x).coerceIn(0f, viewSize.width.toFloat()),
                            (dot.y + y - touch.last.y).coerceIn(0f, viewSize.height.toFloat()),
                        )
                        touch.last.set(x, y)
                    }
                    Tool.Pan -> {}
                }
                for (h in 0 until ev.historySize) sample(ev.getHistoricalX(h), ev.getHistoricalY(h))
                sample(ev.x, ev.y)
                if (active == Tool.Pen && (touch.matcher?.version != touch.drawnVersion)) refreshDraft()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touch.multiTouch) endGesture(commit = ev.actionMasked == MotionEvent.ACTION_UP)
                touch.multiTouch = false
                m.uiSettings.isScrollGesturesEnabled = false
            }
        }
    }

    // ---- UI ----------------------------------------------------------------------------------------------

    Box(Modifier.fillMaxSize().onSizeChanged { viewSize = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.getMapAsync { m -> map = m }
                mapView.setOnTouchListener { _, ev -> onMapTouch(ev); false }
                mapView
            },
        )

        if (tool != Tool.Mark) {
            if (searching) {
                SearchPanel(
                    query = query,
                    onQuery = { query = it },
                    hits = hits,
                    indexReady = searchIndex != null,
                    onPick = ::pick,
                    onClose = ::closeSearch,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(12.dp),
                )
            } else {
                Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
                    Row {
                        LayersButton(onClick = { showLayers = !showLayers })
                        Spacer(Modifier.width(8.dp))
                        SearchButton(onClick = {
                            showLayers = false
                            vm.loadSearchIndex()
                            searching = true
                        })
                    }
                    if (showLayers) {
                        Spacer(Modifier.height(8.dp))
                        LayersPanel(
                            hidden = hidden,
                            onToggle = { g ->
                                hidden = if (g in hidden) hidden - g else hidden + g
                                prefs.hiddenLayers = hidden
                            },
                            drawingLayers = vm.layers,
                            hiddenDrawing = hiddenDrawing,
                            activeLayerId = vm.activeLayerId,
                            strokeCounts = vm.layers.associate { it.id to vm.strokeCount(it.id) },
                            onToggleDrawing = { id -> setDrawingLayerShown(id, id in hiddenDrawing) },
                            onEditLayer = { editingLayerId = it },
                            onAddLayer = { vm.addLayer()?.let { editingLayerId = it.id } },
                            onBackup = { showBackup = true },
                            onAbout = { showAbout = true },
                            onClose = { showLayers = false },
                        )
                    }
                }
            }
        }
        if (tool == Tool.Mark) {
            CrosshairOverlay(dot, Modifier.fillMaxSize())
            Hint("Drag to position, then tap Mark to place", Modifier.align(Alignment.TopCenter))
        }
        if (tool == Tool.Eraser) {
            eraserAt?.let { EraserRing(it, eraserRadiusPx, Modifier.fillMaxSize()) }
        }
        if (tool == Tool.Pen || tool == Tool.Eraser) {
            HistoryPill(
                canUndo = vm.canUndo,
                canRedo = vm.canRedo,
                onUndo = vm::undo,
                onRedo = vm::redo,
                onClearAll = if (tool == Tool.Eraser) ({ confirmClear = true }) else null,
                clearEnabled = vm.strokes.any { it.layerId !in hiddenDrawing },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val openMarker = vm.markers.firstOrNull { it.id == openNoteId }
            if (openMarker != null) {
                // Keyed on the marker so switching markers starts a fresh draft.
                androidx.compose.runtime.key(openMarker.id) {
                    NotePad(
                        modifier = Modifier.onSizeChanged { noteHeightPx = it.height },
                        savedNote = openMarker.note,
                        onSave = { vm.updateNote(openMarker.id, it); openNoteId = null },
                        onNavigate = { navigateTo(openMarker.lat, openMarker.lon) },
                        onDelete = { vm.delete(openMarker.id); openNoteId = null },
                        onClose = { openNoteId = null },
                    )
                }
            } else {
                if (tool == Tool.Mark) {
                    SmallFloatingActionButton(
                        onClick = { tool = Tool.Pan },
                        modifier = Modifier.align(Alignment.End),
                    ) { Icon(Icons.Default.Close, contentDescription = "Cancel placing") }
                }
                if (tool == Tool.Pen) {
                    if (vm.layers.size > 1) {
                        LayerChips(vm.layers, vm.activeLayerId, hiddenDrawing, onSelect = ::selectDrawingLayer, modifier = Modifier.align(Alignment.End))
                    }
                    SnapToggle(snap, ready = vm.roads != null, onToggle = { snap = !snap; prefs.snap = snap }, modifier = Modifier.align(Alignment.End))
                    PenPalette(penColor, onSelect = { penColor = it; prefs.penColor = it })
                }
                if (tool == Tool.Eraser) Hint(if (hiddenDrawing.isEmpty()) "Drag over a highlight to erase it" else "Erases layers that are showing", atTop = false)
                if (tool == Tool.Pan) tapInfo?.let { Hint(it, atTop = false) }
                ToolBar(
                    tool = tool,
                    penColor = penColor,
                    showMe = locationReady,
                    onPen = {
                        if (tool != Tool.Pen) selectDrawingLayer(vm.activeLayerId)
                        tool = if (tool == Tool.Pen) Tool.Pan else Tool.Pen
                    },
                    onEraser = { tool = if (tool == Tool.Eraser) Tool.Pan else Tool.Eraser },
                    onMark = ::onMarkPressed,
                    onMe = {
                        val last = map?.locationComponent?.lastKnownLocation ?: return@ToolBar
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), 15.0)
                        )
                    },
                )
            }
        }
    }

    if (showBackup) {
        BackupDialog(
            markerCount = vm.markers.size,
            strokeCount = vm.strokes.size,
            lastBackupAt = prefs.lastBackupAt,
            ready = vm.loaded,
            onExport = {
                showBackup = false
                exportLauncher.launch("house-hunter-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
            },
            onImport = {
                showBackup = false
                importLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showBackup = false },
        )
    }
    pendingImport?.let { backup ->
        if (confirmReplace) {
            ConfirmReplaceDialog(
                currentMarkers = vm.markers.size,
                currentStrokes = vm.strokes.size,
                onReplace = { finishImport(backup, replace = true) },
                onCancel = { confirmReplace = false },
            )
        } else {
            ImportChoiceDialog(
                backup = backup,
                onAdd = { finishImport(backup, replace = false) },
                onReplace = { confirmReplace = true },
                onCancel = { pendingImport = null },
            )
        }
    }

    editingLayerId?.let { id ->
        vm.layers.firstOrNull { it.id == id }?.let { layer ->
            LayerEditDialog(
                layer = layer,
                isActive = layer.id == vm.activeLayerId,
                canDelete = vm.layers.size > 1,
                strokeCount = vm.strokeCount(layer.id),
                onSave = { vm.renameLayer(layer.id, it); editingLayerId = null },
                onMakeActive = { selectDrawingLayer(layer.id) },
                onDelete = { vm.removeLayer(layer.id); editingLayerId = null },
                onDismiss = { editingLayerId = null },
            )
        } ?: run { editingLayerId = null }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all highlights?") },
            text = { Text(if (hiddenDrawing.isEmpty()) "Every highlight you've drawn will be removed. You can undo this right after." else "Every highlight on the layers that are showing will be removed. Layers that are switched off are left alone. You can undo this right after.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearStrokes(hiddenDrawing) }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, atTop: Boolean = true) {
    Surface(
        modifier = (if (atTop) modifier.statusBarsPadding().padding(12.dp) else modifier),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE6FFFFFF),
        shadowElevation = 4.dp,
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * The neighborhood under a tap, described for a one-line label: the finest outline there is (a subdivision, then a
 * civic-association area, then an official Atlanta neighborhood), or null if the tap landed on none.
 */
private fun neighborhoodAt(map: MapLibreMap, point: PointF): String? {
    val box = RectF(point.x - 2f, point.y - 2f, point.x + 2f, point.y + 2f)
    val hits = map.queryRenderedFeatures(box, "subdivision-fill", "civic-fill", "neighborhood-fill", "school-zone-fill", "school-zone-upper-fill")
    fun text(f: org.maplibre.geojson.Feature, key: String) = if (f.hasProperty(key)) f.getStringProperty(key) else null
    val subdivision = hits.firstOrNull { text(it, "scale") == "subdivision" }
    val civic = hits.firstOrNull { text(it, "scale") == "neighborhood" }
    val atlanta = hits.firstOrNull { text(it, "NAME") != null }
    val place = when {
        subdivision != null -> listOfNotNull(text(subdivision, "name"), civic?.let { text(it, "name") }, text(subdivision, "city"))
            .joinToString(" · ") + " (subdivision)"
        civic != null -> "${text(civic, "name")} · ${text(civic, "city")}"
        atlanta != null -> listOfNotNull(text(atlanta, "NAME"), text(atlanta, "NPU")?.let { "NPU $it" }, "Atlanta").joinToString(" · ")
        else -> null
    }
    // School zones only appear here while that layer is switched on. Decatur has two (K-2 and 3-5); the rest have one.
    val zones = hits.filter { text(it, "district") != null }
    val school = zones.firstOrNull()?.let { first ->
        val district = when (text(first, "district")) {
            "Atlanta Public Schools" -> "APS"
            "DeKalb County School District" -> "DeKalb"
            "Fulton County Schools" -> "Fulton"
            "City Schools of Decatur" -> "Decatur"
            "Cobb County School District" -> "Cobb"
            "Marietta City Schools" -> "Marietta"
            else -> text(first, "district")
        }
        val names = zones.mapNotNull { text(it, "name") }.distinct().sortedBy { it.contains("(3-5)") }
        "Elementary zone: ${names.joinToString(" · ")} ($district)"
    }
    return listOfNotNull(place, school).joinToString("\n").ifEmpty { null }
}
