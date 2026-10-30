package com.househunter.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** A pin dropped on the map with a free-text note (requirements §8). */
data class NoteMarker(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

/** First non-blank line of the note, shortened for the map label. Empty when the note is blank. */
fun notePreview(note: String, maxChars: Int = 28): String {
    val line = note.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
    return if (line.length <= maxChars) line else line.take(maxChars - 1).trimEnd() + "…"
}

/**
 * Markers live in one small JSON file in app storage; nothing leaves the device (NFR-3).
 * Saves go to a temp file first and are then renamed over the real one, so a crash mid-save can't corrupt it.
 */
class MarkerStore(context: Context) {
    private val file = File(context.filesDir, "markers.json")

    fun load(): List<NoteMarker> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { StoreJson.marker(array.getJSONObject(it)) }
        } catch (e: Exception) {
            // Don't silently discard an unreadable file: keep it aside so nothing is lost for good.
            file.renameTo(File(file.parentFile, "markers.corrupt-${System.currentTimeMillis()}.json"))
            emptyList()
        }
    }

    fun save(markers: List<NoteMarker>) {
        val array = JSONArray()
        markers.forEach { array.put(StoreJson.marker(it)) }
        val tmp = File(file.parentFile, "markers.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
