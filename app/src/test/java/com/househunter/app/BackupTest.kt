package com.househunter.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val marker = NoteMarker(id = "m1", lat = 33.784652, lon = -84.384247, note = "Walkable block, near Piedmont Park", createdAt = 100, updatedAt = 200)
    private val stroke = Stroke(id = "s1", color = 2, parts = listOf(
        listOf(LatLon(33.785376, -84.382176), LatLon(33.784303, -84.382244), LatLon(33.783108, -84.382342)),
        listOf(LatLon(33.7826, -84.3830), LatLon(33.7821, -84.3831)),
    ))
    private val backup = Backup(listOf(marker), listOf(stroke), exportedAt = 1_789_000_000_000)

    private fun rejects(text: String, mention: String? = null) {
        try {
            BackupCodec.decode(text)
            fail("should have been rejected: ${text.take(80)}")
        } catch (e: BackupFormatException) {
            if (mention != null) assertTrue("message '${e.message}' should mention '$mention'", e.message!!.contains(mention))
        }
    }

    /** Edits one field of a valid backup, to build a damaged file from a good one. */
    private fun tampered(edit: (JSONObject) -> Unit) = JSONObject(BackupCodec.encode(backup)).also(edit).toString()

    @Test fun `a backup survives the round trip exactly`() {
        val back = BackupCodec.decode(BackupCodec.encode(backup))
        assertEquals(backup, back)
    }

    @Test fun `awkward note text survives, quotes and emoji and blank lines included`() {
        val tricky = marker.copy(note = "Said \"quiet at night\"\n\n  - 3 cafés ☕\n\ttab, back\\slash, 日本語, 🏡")
        val back = BackupCodec.decode(BackupCodec.encode(Backup(listOf(tricky), emptyList(), 1)))
        assertEquals(tricky.note, back.markers[0].note)
    }

    @Test fun `an empty backup is fine`() {
        val back = BackupCodec.decode(BackupCodec.encode(Backup(emptyList(), emptyList(), 5)))
        assertTrue(back.markers.isEmpty() && back.strokes.isEmpty())
    }

    @Test fun `files that are not backups are turned away politely`() {
        rejects("hello", "isn't a House Hunter backup")
        rejects("", "isn't a House Hunter backup")
        rejects("[1,2,3]", "isn't a House Hunter backup")
        rejects("""{"markers":[],"strokes":[]}""", "isn't a House Hunter backup")
        rejects("""{"app":"Other App","version":1}""", "isn't a House Hunter backup")
        rejects("""{"app":"House Hunter"}""", "isn't a House Hunter backup")
    }

    @Test fun `a backup from a newer app version says so`() {
        rejects(tampered { it.put("version", BackupCodec.VERSION + 1) }, "newer version")
    }

    @Test fun `one bad item rejects the whole file, so nothing is half imported`() {
        rejects(tampered { it.getJSONArray("markers").put(JSONObject().put("id", "bad")) }, "damaged")
    }

    @Test fun `impossible coordinates are rejected`() {
        rejects(tampered { it.getJSONArray("markers").getJSONObject(0).put("lat", 123.0) }, "damaged")
        rejects(tampered { it.getJSONArray("markers").getJSONObject(0).put("lon", -400.0) }, "damaged")
        rejects(tampered { it.getJSONArray("strokes").getJSONObject(0).getJSONArray("parts").getJSONArray(0).getJSONArray(0).put(0, 95.0) }, "damaged")
    }

    @Test fun `strokes must use one of the six pen colors and have at least two points per line`() {
        rejects(tampered { it.getJSONArray("strokes").getJSONObject(0).put("color", 6) }, "damaged")
        rejects(tampered { it.getJSONArray("strokes").getJSONObject(0).put("color", -1) }, "damaged")
        rejects(tampered { it.getJSONArray("strokes").getJSONObject(0).getJSONArray("parts").getJSONArray(1).remove(1) }, "damaged")
    }

    @Test fun `duplicate ids are rejected`() {
        val dup = tampered { it.getJSONArray("markers").put(it.getJSONArray("markers").getJSONObject(0)) }
        rejects(dup, "damaged")
    }

    // ---- merging ----

    private val mine = NoteMarker(id = "a", lat = 33.78, lon = -84.38, note = "mine", createdAt = 1, updatedAt = 10)

    @Test fun `importing adds notes and highlights the phone does not have`() {
        val theirs = NoteMarker(id = "b", lat = 33.79, lon = -84.39, note = "new one", createdAt = 2, updatedAt = 2)
        val result = BackupMerge.merge(listOf(mine), listOf(stroke), DrawingLayers.initial(), Backup(listOf(theirs), listOf(stroke.copy(id = "s2")), 0))
        assertEquals(listOf("a", "b"), result.markers.map { it.id })
        assertEquals(listOf("s1", "s2"), result.strokes.map { it.id })
        assertEquals(1, result.addedMarkers); assertEquals(0, result.updatedMarkers); assertEquals(1, result.addedStrokes)
    }

    @Test fun `a note on both keeps whichever copy was edited last`() {
        val newer = mine.copy(note = "edited after backup", updatedAt = 20)
        val olderBackup = Backup(listOf(mine.copy(note = "old", updatedAt = 5)), emptyList(), 0)
        assertEquals("edited after backup", BackupMerge.merge(listOf(newer), emptyList(), DrawingLayers.initial(), olderBackup).markers[0].note)

        val newerBackup = Backup(listOf(mine.copy(note = "written on the old phone", updatedAt = 30)), emptyList(), 0)
        val merged = BackupMerge.merge(listOf(newer), emptyList(), DrawingLayers.initial(), newerBackup)
        assertEquals("written on the old phone", merged.markers[0].note)
        assertEquals(1, merged.updatedMarkers)
    }

    @Test fun `importing the same backup twice changes nothing`() {
        val once = BackupMerge.merge(emptyList(), emptyList(), DrawingLayers.initial(), backup)
        val twice = BackupMerge.merge(once.markers, once.strokes, once.layers, backup)
        assertEquals(once.markers, twice.markers)
        assertEquals(once.strokes, twice.strokes)
        assertEquals(0, twice.addedMarkers + twice.updatedMarkers + twice.addedStrokes)
    }

    @Test fun `a highlight on both keeps the phone's copy, which may have been erased since`() {
        val erased = stroke.copy(parts = listOf(stroke.parts[0]))
        val result = BackupMerge.merge(emptyList(), listOf(erased), DrawingLayers.initial(), backup)
        assertEquals(erased, result.strokes.single())
    }

    // ---- drawing layers ----

    private val schools = DrawingLayer("layer-schools", "Schools I like")

    @Test fun `layers and which layer each highlight is on survive the round trip`() {
        val two = Backup(listOf(marker), listOf(stroke, stroke.copy(id = "s2", layerId = schools.id)), 1, DrawingLayers.initial() + schools)
        val back = BackupCodec.decode(BackupCodec.encode(two))
        assertEquals(two, back)
        assertEquals(schools.id, back.strokes[1].layerId)
    }

    @Test fun `a version 1 backup with no layers still imports onto the first layer`() {
        val v1 = JSONObject(BackupCodec.encode(backup)).also { it.put("version", 1); it.remove("layers"); it.getJSONArray("strokes").getJSONObject(0).remove("layer") }.toString()
        val back = BackupCodec.decode(v1)
        assertEquals(DrawingLayers.initial(), back.layers)
        assertEquals(DrawingLayers.DEFAULT_ID, back.strokes[0].layerId)
    }

    @Test fun `layer problems in a backup are rejected`() {
        rejects(tampered { it.getJSONArray("strokes").getJSONObject(0).put("layer", "nope") }, "damaged")
        rejects(tampered { it.put("layers", org.json.JSONArray()) }, "damaged")
        rejects(tampered { it.getJSONArray("layers").getJSONObject(0).put("name", "  ") }, "damaged")
        rejects(tampered { it.getJSONArray("layers").getJSONObject(0).put("name", "x".repeat(DrawingLayers.MAX_NAME + 1)) }, "damaged")
        rejects(tampered { it.put("layers", org.json.JSONArray().also { a -> repeat(DrawingLayers.MAX + 1) { i -> a.put(JSONObject().put("id", "l$i").put("name", "L$i")) } }) }, "damaged")
        rejects(tampered { it.getJSONArray("layers").put(JSONObject().put("id", DrawingLayers.DEFAULT_ID).put("name", "dup")) }, "damaged")
    }

    @Test fun `importing brings in the backup's layers and puts highlights on the right one`() {
        val theirs = Backup(emptyList(), listOf(stroke.copy(id = "n1", layerId = schools.id)), 0, DrawingLayers.initial() + schools)
        val result = BackupMerge.merge(emptyList(), listOf(stroke), DrawingLayers.initial(), theirs)
        assertEquals(listOf(DrawingLayers.DEFAULT_ID, schools.id), result.layers.map { it.id })
        assertEquals(schools.id, result.strokes.single { it.id == "n1" }.layerId)
    }

    @Test fun `when the phone has no room for a backup layer its highlights go to the first layer`() {
        val full = listOf("a", "b", "c", "d").map { DrawingLayer("layer-$it", it) }
        val theirs = Backup(emptyList(), listOf(stroke.copy(id = "n1", layerId = schools.id)), 0, DrawingLayers.initial() + schools)
        val result = BackupMerge.merge(emptyList(), emptyList(), full, theirs)
        assertEquals(4, result.layers.size)
        assertEquals("layer-a", result.strokes.single().layerId)
    }
}
