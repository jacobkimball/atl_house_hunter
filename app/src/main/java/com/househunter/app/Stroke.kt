package com.househunter.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/**
 * A highlighter stroke (requirements §6). It is stored as map coordinates so it stays glued to the streets
 * when the map moves. Erasing can cut a stroke into several pieces, hence a list of polylines.
 */
data class Stroke(
    val id: String = UUID.randomUUID().toString(),
    val color: Int,
    val parts: List<List<LatLon>>,
    /** Which of my drawing layers this highlight belongs to. */
    val layerId: String = DrawingLayers.DEFAULT_ID,
)

/** A named set of highlights that can be shown or hidden together (up to [DrawingLayers.MAX]). */
data class DrawingLayer(val id: String, val name: String)

/** The rules for my drawing layers, kept apart from the screen so they can be tested. */
object DrawingLayers {
    const val MAX = 4
    const val DEFAULT_ID = "layer-1"
    const val MAX_NAME = 30

    fun initial() = listOf(DrawingLayer(DEFAULT_ID, "Layer 1"))

    fun canAdd(layers: List<DrawingLayer>) = layers.size < MAX

    /** Trims, squeezes spaces, caps the length; falls back if nothing is left. */
    fun cleanName(raw: String, fallback: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).trim().ifEmpty { fallback }

    fun nextDefaultName(layers: List<DrawingLayer>): String {
        var n = layers.size + 1
        while (layers.any { it.name.equals("Layer $n", ignoreCase = true) }) n++
        return "Layer $n"
    }

    /** The list with one more layer, or null when already at the limit. */
    fun add(layers: List<DrawingLayer>): List<DrawingLayer>? {
        if (!canAdd(layers)) return null
        var id: String
        do id = "layer-" + UUID.randomUUID().toString().take(8) while (layers.any { it.id == id })
        return layers + DrawingLayer(id, nextDefaultName(layers))
    }

    fun rename(layers: List<DrawingLayer>, id: String, name: String) =
        layers.map { if (it.id == id) it.copy(name = cleanName(name, it.name)) else it }

    /** Removes a layer and its highlights. The last remaining layer can't be removed (returns null). */
    fun remove(layers: List<DrawingLayer>, strokes: List<Stroke>, id: String): Pair<List<DrawingLayer>, List<Stroke>>? {
        if (layers.size <= 1 || layers.none { it.id == id }) return null
        return layers.filter { it.id != id } to strokes.filter { it.layerId != id }
    }

    /** Makes loaded data safe: at least one layer, at most [MAX], and every highlight on a layer that exists. */
    fun normalize(layers: List<DrawingLayer>, strokes: List<Stroke>): Pair<List<DrawingLayer>, List<Stroke>> {
        val kept = layers.distinctBy { it.id }.take(MAX).ifEmpty { initial() }
        val ids = kept.map { it.id }.toSet()
        val home = kept.first().id
        return kept to strokes.map { if (it.layerId in ids) it else it.copy(layerId = home) }
    }
}

/** The six pen colors, in the order requested: red, orange, yellow, green, blue, purple. */
object PenColors {
    val hex = listOf("#E03131", "#F76707", "#FCC419", "#2F9E44", "#1C7ED6", "#7048E8")
    val names = listOf("Red", "Orange", "Yellow", "Green", "Blue", "Purple")
}

/** Erasing by distance, like a real eraser: everything inside the circle goes, the rest of a stroke stays. */
object StrokeEraser {
    private const val METERS_PER_DEG_LAT = 110_540.0
    private const val METERS_PER_DEG_LON_AT_EQUATOR = 111_320.0

    /** Returns [strokes] itself when nothing was within [radiusMeters] of [center], so callers can skip work. */
    fun erase(strokes: List<Stroke>, center: LatLon, radiusMeters: Double): List<Stroke> {
        val frame = Frame(center)
        var changed = false
        val out = ArrayList<Stroke>(strokes.size)
        for (stroke in strokes) {
            val newParts = ArrayList<List<LatLon>>()
            var strokeChanged = false
            for (part in stroke.parts) {
                val clipped = clip(part, frame, radiusMeters)
                if (clipped == null) newParts.add(part) else {
                    strokeChanged = true
                    newParts.addAll(clipped)
                }
            }
            if (!strokeChanged) out.add(stroke) else {
                changed = true
                if (newParts.isNotEmpty()) out.add(stroke.copy(parts = newParts))
            }
        }
        return if (changed) out else strokes
    }

    /** A flat local frame in meters around the eraser, accurate enough over a few hundred meters. */
    private class Frame(val center: LatLon) {
        private val lonScale = METERS_PER_DEG_LON_AT_EQUATOR * cos(Math.toRadians(center.lat))
        fun x(p: LatLon) = (p.lon - center.lon) * lonScale
        fun y(p: LatLon) = (p.lat - center.lat) * METERS_PER_DEG_LAT
        fun at(a: LatLon, b: LatLon, t: Double) =
            LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** The polyline with the circle cut out (possibly several pieces), or null if the circle never touches it. */
    private fun clip(points: List<LatLon>, f: Frame, r: Double): List<List<LatLon>>? {
        if (points.isEmpty()) return null
        val pieces = ArrayList<List<LatLon>>()
        var current = ArrayList<LatLon>()
        var touched = false
        fun flush() {
            if (current.size >= 2) pieces.add(current)
            current = ArrayList()
        }

        if (hypot(f.x(points[0]), f.y(points[0])) < r) touched = true else current.add(points[0])
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val hit = circleInterval(f.x(a), f.y(a), f.x(b), f.y(b), r)
            if (hit == null) {
                current.add(b)
                continue
            }
            touched = true
            val (t1, t2) = hit
            if (t1 > 1e-9) current.add(f.at(a, b, t1))
            flush()
            if (t2 < 1 - 1e-9) {
                current.add(f.at(a, b, t2))
                current.add(b)
            }
        }
        flush()
        return if (touched) pieces else null
    }

    /** The stretch [t1, t2] of segment a->b (0..1) that lies inside the circle at the origin, or null. */
    private fun circleInterval(ax: Double, ay: Double, bx: Double, by: Double, r: Double): Pair<Double, Double>? {
        val dx = bx - ax
        val dy = by - ay
        val a = dx * dx + dy * dy
        if (a < 1e-12) return if (hypot(ax, ay) < r) 0.0 to 1.0 else null
        val b = 2 * (ax * dx + ay * dy)
        val c = ax * ax + ay * ay - r * r
        val disc = b * b - 4 * a * c
        if (disc <= 0) return null
        val root = sqrt(disc)
        val t1 = (-b - root) / (2 * a)
        val t2 = (-b + root) / (2 * a)
        if (t2 <= 0 || t1 >= 1) return null
        return maxOf(t1, 0.0) to minOf(t2, 1.0)
    }
}

/** Thins out a finger path (screen pixels) with Douglas-Peucker so strokes stay small on disk and fast to draw. */
object PathSimplifier {
    data class Pt(val x: Double, val y: Double)

    fun simplify(points: List<Pt>, tolerance: Double): List<Pt> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size).also { it[0] = true; it[points.size - 1] = true }
        val stack = ArrayDeque<IntArray>().apply { addLast(intArrayOf(0, points.size - 1)) }
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            var far = -1
            var farDist = tolerance
            for (i in lo + 1 until hi) {
                val d = distanceToSegment(points[i], points[lo], points[hi])
                if (d > farDist) { far = i; farDist = d }
            }
            if (far >= 0) {
                keep[far] = true
                stack.addLast(intArrayOf(lo, far))
                stack.addLast(intArrayOf(far, hi))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distanceToSegment(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/** Strokes live in one small JSON file next to markers.json; same crash-safe save approach. */
class StrokeStore(context: Context) {
    private val file = File(context.filesDir, "strokes.json")

    fun load(): List<Stroke> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { StoreJson.stroke(array.getJSONObject(it)) }
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "strokes.corrupt-${System.currentTimeMillis()}.json"))
            emptyList()
        }
    }

    fun save(strokes: List<Stroke>) {
        val array = JSONArray()
        strokes.forEach { array.put(StoreJson.stroke(it)) }
        val tmp = File(file.parentFile, "strokes.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

}

/** Layer names live in layers.json next to strokes.json; same crash-safe save. */
class LayerStore(context: Context) {
    private val file = File(context.filesDir, "layers.json")

    fun load(): List<DrawingLayer> {
        if (!file.exists()) return DrawingLayers.initial()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { i -> StoreJson.layer(array.getJSONObject(i)) }
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "layers.corrupt-${System.currentTimeMillis()}.json"))
            DrawingLayers.initial()
        }
    }

    fun save(layers: List<DrawingLayer>) {
        val array = JSONArray()
        layers.forEach { array.put(StoreJson.layer(it)) }
        val tmp = File(file.parentFile, "layers.json.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
