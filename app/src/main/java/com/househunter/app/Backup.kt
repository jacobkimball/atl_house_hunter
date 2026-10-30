package com.househunter.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Everything worth keeping: the notes and the highlights. Map data and settings can be rebuilt. */
data class Backup(
    val markers: List<NoteMarker>,
    val strokes: List<Stroke>,
    val exportedAt: Long,
    val layers: List<DrawingLayer> = DrawingLayers.initial(),
)

class BackupFormatException(message: String) : Exception(message)

/** JSON for one marker or stroke; shared by the on-phone files and the export file so they can never drift apart. */
object StoreJson {
    fun marker(m: NoteMarker): JSONObject = JSONObject()
        .put("id", m.id).put("lat", m.lat).put("lon", m.lon).put("note", m.note)
        .put("createdAt", m.createdAt).put("updatedAt", m.updatedAt)

    fun marker(o: JSONObject) = NoteMarker(
        id = o.getString("id"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
        note = o.optString("note", ""), createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
    )

    fun layer(l: DrawingLayer): JSONObject = JSONObject().put("id", l.id).put("name", l.name)

    fun layer(o: JSONObject) = DrawingLayer(o.getString("id"), o.getString("name"))

    fun stroke(s: Stroke): JSONObject {
        val parts = JSONArray()
        s.parts.forEach { part ->
            val pts = JSONArray()
            part.forEach { pts.put(JSONArray().put(round6(it.lat)).put(round6(it.lon))) }
            parts.put(pts)
        }
        return JSONObject().put("id", s.id).put("color", s.color).put("layer", s.layerId).put("parts", parts)
    }

    fun stroke(o: JSONObject): Stroke {
        val parts = o.getJSONArray("parts")
        return Stroke(
            id = o.getString("id"),
            color = o.getInt("color"),
            layerId = o.optString("layer", DrawingLayers.DEFAULT_ID),
            parts = List(parts.length()) { p ->
                val pts = parts.getJSONArray(p)
                List(pts.length()) { k -> pts.getJSONArray(k).let { LatLon(it.getDouble(0), it.getDouble(1)) } }
            },
        )
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
}

/** The export file: one JSON document, readable in any text editor. */
object BackupCodec {
    /** Version 2 added drawing layers. Version 1 files have none: everything lands on the first layer. */
    const val VERSION = 2
    private const val APP = "House Hunter"
    private const val MAX_NOTE_CHARS = 200_000

    fun encode(backup: Backup): String = JSONObject()
        .put("app", APP)
        .put("version", VERSION)
        .put("exportedAt", backup.exportedAt)
        .put("layers", JSONArray().also { a -> backup.layers.forEach { a.put(StoreJson.layer(it)) } })
        .put("markers", JSONArray().also { a -> backup.markers.forEach { a.put(StoreJson.marker(it)) } })
        .put("strokes", JSONArray().also { a -> backup.strokes.forEach { a.put(StoreJson.stroke(it)) } })
        .toString(1)

    /** Reads an export file, or throws [BackupFormatException] with a message fit to show. Nothing is half-applied. */
    fun decode(text: String): Backup {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw BackupFormatException("That file isn't a House Hunter backup.")
        }
        if (root.optString("app") != APP) throw BackupFormatException("That file isn't a House Hunter backup.")
        val version = root.optInt("version", -1)
        if (version < 1) throw BackupFormatException("That file isn't a House Hunter backup.")
        if (version > VERSION) throw BackupFormatException("That backup was made by a newer version of House Hunter. Update the app first.")

        try {
            val markerArray = root.optJSONArray("markers") ?: JSONArray()
            val markers = List(markerArray.length()) { StoreJson.marker(markerArray.getJSONObject(it)) }
            val strokeArray = root.optJSONArray("strokes") ?: JSONArray()
            val strokes = List(strokeArray.length()) { StoreJson.stroke(strokeArray.getJSONObject(it)) }
            val layerArray = root.optJSONArray("layers")
            val layers = if (layerArray == null) DrawingLayers.initial() else List(layerArray.length()) { StoreJson.layer(layerArray.getJSONObject(it)) }
            if (layers.isEmpty() || layers.size > DrawingLayers.MAX) throw damaged()
            if (layers.any { it.id.isBlank() || it.name.isBlank() || it.name.length > DrawingLayers.MAX_NAME } ||
                layers.map { it.id }.toSet().size != layers.size) throw damaged()
            if (strokes.any { st -> layers.none { it.id == st.layerId } }) throw damaged()
            markers.forEach { m ->
                if (m.id.isBlank() || !validPoint(m.lat, m.lon) || m.note.length > MAX_NOTE_CHARS) throw damaged()
            }
            strokes.forEach { s ->
                if (s.id.isBlank() || s.color !in PenColors.hex.indices) throw damaged()
                if (s.parts.any { part -> part.size < 2 || part.any { !validPoint(it.lat, it.lon) } }) throw damaged()
            }
            if (markers.map { it.id }.toSet().size != markers.size || strokes.map { it.id }.toSet().size != strokes.size) throw damaged()
            return Backup(markers, strokes, root.optLong("exportedAt"), layers)
        } catch (e: JSONException) {
            throw damaged()
        }
    }

    private fun damaged() = BackupFormatException("That backup file looks damaged, so nothing was imported.")
    private fun validPoint(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0 && lat.isFinite() && lon.isFinite()
}

/** Combining an imported backup with what's already on the phone. */
object BackupMerge {
    class Result(
        val markers: List<NoteMarker>,
        val strokes: List<Stroke>,
        val layers: List<DrawingLayer>,
        val addedMarkers: Int,
        val updatedMarkers: Int,
        val addedStrokes: Int,
    )

    /**
     * Adds whatever the backup has that the phone doesn't (matched by id). A marker on both keeps whichever copy was
     * edited most recently. A highlight on both keeps the phone's copy, which may have been erased since.
     * Drawing layers are matched by id too; there is room for only [DrawingLayers.MAX], so a backup layer that
     * doesn't fit has its highlights placed on the phone's first layer instead of being lost.
     */
    fun merge(markers: List<NoteMarker>, strokes: List<Stroke>, layers: List<DrawingLayer>, backup: Backup): Result {
        val byId = LinkedHashMap<String, NoteMarker>()
        markers.forEach { byId[it.id] = it }
        var added = 0
        var updated = 0
        for (m in backup.markers) {
            val mine = byId[m.id]
            when {
                mine == null -> { byId[m.id] = m; added++ }
                m.updatedAt > mine.updatedAt -> { byId[m.id] = m; updated++ }
            }
        }

        var mergedLayers = layers
        for (l in backup.layers) {
            if (mergedLayers.none { it.id == l.id } && DrawingLayers.canAdd(mergedLayers)) mergedLayers = mergedLayers + l
        }
        val layerIds = mergedLayers.map { it.id }.toSet()
        val haveStroke = strokes.map { it.id }.toSet()
        val newStrokes = backup.strokes.filter { it.id !in haveStroke }
            .map { if (it.layerId in layerIds) it else it.copy(layerId = mergedLayers.first().id) }
        return Result(byId.values.toList(), strokes + newStrokes, mergedLayers, added, updated, newStrokes.size)
    }
}
