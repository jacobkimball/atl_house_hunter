package com.househunter.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val store = MarkerStore(app)
    private val strokeStore = StrokeStore(app)
    private val layerStore = LayerStore(app)
    private val prefs = AppPrefs(app)
    private val saveLock = Mutex()
    private val appContext = app

    val markers = mutableStateListOf<NoteMarker>()

    /** Highlighter strokes (requirements §6). Immutable lists, so undo can keep cheap snapshots. */
    var strokes by mutableStateOf<List<Stroke>>(emptyList())
        private set

    /** My drawing layers (up to [DrawingLayers.MAX]) and the one new highlights go onto. */
    var layers by mutableStateOf(DrawingLayers.initial())
        private set
    var activeLayerId by mutableStateOf(prefs.activeLayerId)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayList<List<Stroke>>()
    private val redoStack = ArrayList<List<Stroke>>()
    private var strokesBeforeErase: List<Stroke>? = null
    private var erasedThisGesture = false

    /** The street network for snap-to-street; null until it has loaded (drawing falls back to freehand meanwhile). */
    var roads by mutableStateOf<RoadGraph?>(null)
        private set

    /** Neighborhood/street search data; read the first time the search box opens, since most sessions never search. */
    var searchIndex by mutableStateOf<SearchIndex?>(null)
        private set
    private var searchIndexRequested = false

    fun loadSearchIndex() {
        if (searchIndexRequested) return
        searchIndexRequested = true
        viewModelScope.launch {
            searchIndex = withContext(Dispatchers.Default) {
                appContext.assets.open("search_index.json").use { SearchIndex.parse(it.readBytes().toString(Charsets.UTF_8)) }
            }
        }
    }

    /** False until saved markers and strokes have been read; adding before then could overwrite them. */
    var loaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            roads = withContext(Dispatchers.Default) {
                appContext.assets.open("road_graph.bin").use { RoadGraph.parse(it.readBytes()) }
            }
        }
        viewModelScope.launch {
            val loadedData = withContext(Dispatchers.IO) { Triple(store.load(), strokeStore.load(), layerStore.load()) }
            markers.addAll(loadedData.first)
            val (goodLayers, goodStrokes) = DrawingLayers.normalize(loadedData.third, loadedData.second)
            layers = goodLayers
            strokes = goodStrokes
            if (layers.none { it.id == activeLayerId }) setActiveLayer(layers.first().id)
            loaded = true
        }
    }

    fun add(lat: Double, lon: Double): NoteMarker {
        val marker = NoteMarker(lat = lat, lon = lon)
        markers.add(marker)
        persist()
        return marker
    }

    fun updateNote(id: String, note: String) {
        val i = markers.indexOfFirst { it.id == id }
        if (i < 0) return
        markers[i] = markers[i].copy(note = note, updatedAt = System.currentTimeMillis())
        persist()
    }

    fun delete(id: String) {
        if (markers.removeAll { it.id == id }) persist()
    }

    fun snapshotBackup() = Backup(markers.toList(), strokes, System.currentTimeMillis(), layers)

    /**
     * Brings an imported backup in, either merged with what's here or replacing it. Highlights can be undone
     * afterwards (unless the set of layers changed); markers can't, which is why the caller asks before replacing.
     */
    fun importBackup(backup: Backup, replace: Boolean): BackupMerge.Result? {
        if (!loaded) return null
        val result = if (replace) {
            BackupMerge.Result(backup.markers, backup.strokes, backup.layers, backup.markers.size, 0, backup.strokes.size)
        } else {
            BackupMerge.merge(markers.toList(), strokes, layers, backup)
        }
        markers.clear()
        markers.addAll(result.markers)
        persist()
        val layersChanged = result.layers != layers
        if (layersChanged) {
            // Old undo snapshots could point at layers that no longer exist, so history starts fresh.
            undoStack.clear(); redoStack.clear(); refreshHistoryFlags()
            layers = result.layers
            persistLayers()
            if (layers.none { it.id == activeLayerId }) setActiveLayer(layers.first().id)
        }
        if (replace || result.addedStrokes > 0 || layersChanged) {
            if (!layersChanged) pushUndo(strokes)
            strokes = result.strokes
            persistStrokes()
        }
        return result
    }

    // ---- drawing layers --------------------------------------------------------------------------------------

    fun setActiveLayer(id: String) {
        if (layers.none { it.id == id }) return
        activeLayerId = id
        prefs.activeLayerId = id
    }

    /** Adds a layer (and makes it the one you draw on). Null when the limit is reached. */
    fun addLayer(): DrawingLayer? {
        val next = DrawingLayers.add(layers) ?: return null
        layers = next
        persistLayers()
        setActiveLayer(next.last().id)
        return next.last()
    }

    fun renameLayer(id: String, name: String) {
        layers = DrawingLayers.rename(layers, id, name)
        persistLayers()
    }

    /** Removes a layer and every highlight on it. History is cleared, since older snapshots could refer to it. */
    fun removeLayer(id: String): Boolean {
        val (left, kept) = DrawingLayers.remove(layers, strokes, id) ?: return false
        layers = left
        strokes = kept
        undoStack.clear(); redoStack.clear(); refreshHistoryFlags()
        if (activeLayerId == id) setActiveLayer(left.first().id)
        persistLayers()
        persistStrokes()
        return true
    }

    fun strokeCount(layerId: String) = strokes.count { it.layerId == layerId }

    fun commitStroke(stroke: Stroke) {
        pushUndo(strokes)
        strokes = strokes + stroke
        persistStrokes()
    }

    /** An erase gesture is one undo step, however many strokes it touches; call [beginErase] at finger-down. */
    fun beginErase() {
        strokesBeforeErase = strokes
        erasedThisGesture = false
    }

    /** Erases only on layers that are showing, so nothing changes out of sight. */
    fun erase(center: LatLon, radiusMeters: Double, hiddenLayers: Set<String>) {
        val next = if (hiddenLayers.isEmpty()) {
            StrokeEraser.erase(strokes, center, radiusMeters)
        } else {
            val (hidden, visible) = strokes.partition { it.layerId in hiddenLayers }
            val erased = StrokeEraser.erase(visible, center, radiusMeters)
            if (erased === visible) strokes else erased + hidden
        }
        if (next === strokes) return
        if (!erasedThisGesture) {
            pushUndo(strokesBeforeErase ?: strokes)
            erasedThisGesture = true
        }
        strokes = next
    }

    fun endErase() {
        if (erasedThisGesture) persistStrokes()
        strokesBeforeErase = null
        erasedThisGesture = false
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(strokes)
        strokes = undoStack.removeAt(undoStack.lastIndex)
        refreshHistoryFlags()
        persistStrokes()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(strokes)
        strokes = redoStack.removeAt(redoStack.lastIndex)
        refreshHistoryFlags()
        persistStrokes()
    }

    /** Clears the layers that are showing; hidden layers are left alone. */
    fun clearStrokes(hiddenLayers: Set<String>) {
        val left = strokes.filter { it.layerId in hiddenLayers }
        if (left.size == strokes.size) return
        pushUndo(strokes)
        strokes = left
        persistStrokes()
    }

    private fun pushUndo(before: List<Stroke>) {
        undoStack.add(before)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        redoStack.clear()
        refreshHistoryFlags()
    }

    private fun refreshHistoryFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    private fun persistStrokes() {
        val snapshot = strokes
        viewModelScope.launch(Dispatchers.IO) { saveLock.withLock { strokeStore.save(snapshot) } }
    }

    private fun persistLayers() {
        val snapshot = layers
        viewModelScope.launch(Dispatchers.IO) { saveLock.withLock { layerStore.save(snapshot) } }
    }

    /** Written immediately on every change, so a crash or swipe-away never loses work (NFR-4). */
    private fun persist() {
        val snapshot = markers.toList()
        viewModelScope.launch(Dispatchers.IO) { saveLock.withLock { store.save(snapshot) } }
    }

    private companion object {
        const val MAX_UNDO = 50
    }
}
