package com.househunter.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A flat meters grid over the coverage area. Distances between nearby points are accurate to well under 1%. */
object LocalFrame {
    private const val LAT0 = 33.81
    private const val LON0 = -84.40
    private const val METERS_PER_DEG_LAT = 110_540.0
    private val metersPerDegLon = 111_320.0 * Math.cos(Math.toRadians(LAT0))

    fun x(lon: Double) = ((lon - LON0) * metersPerDegLon).toFloat()
    fun y(lat: Double) = ((lat - LAT0) * METERS_PER_DEG_LAT).toFloat()
    fun lon(x: Double) = LON0 + x / metersPerDegLon
    fun lat(y: Double) = LAT0 + y / METERS_PER_DEG_LAT
}

/**
 * The street network (built by data/build_road_graph.py). Streets are cut into edges at every junction, so a
 * matcher can follow a street through its curves and only change streets at real intersections.
 * Geometry is stored as meters in [LocalFrame]; edge vertices run from [edgeFrom] to [edgeTo].
 */
class RoadGraph(
    val nodeCount: Int,
    val edgeFrom: IntArray,
    val edgeTo: IntArray,
    /** First vertex of each edge; one extra entry at the end. */
    val edgeVertStart: IntArray,
    val edgeClass: ByteArray,
    val edgeName: IntArray,
    val vx: FloatArray,
    val vy: FloatArray,
    val names: List<String>,
) {
    val edgeCount = edgeFrom.size

    /** Distance in meters from the start of the vertex's edge. */
    val cum = FloatArray(vx.size)
    val edgeLength = FloatArray(edgeCount)
    val vertEdge = IntArray(vx.size)

    private val nodeEdgeStart = IntArray(nodeCount + 1)
    private val nodeEdges: IntArray

    private val cellSize = 50f
    private val gridMinX: Float
    private val gridMinY: Float
    private val cols: Int
    private val rows: Int
    private val cellStart: IntArray
    private val cellItems: IntArray

    init {
        for (e in 0 until edgeCount) {
            val first = edgeVertStart[e]
            val end = edgeVertStart[e + 1]
            var run = 0f
            for (v in first until end) {
                vertEdge[v] = e
                if (v > first) run += hypot(vx[v] - vx[v - 1], vy[v] - vy[v - 1])
                cum[v] = run
            }
            edgeLength[e] = run
        }

        for (e in 0 until edgeCount) {
            nodeEdgeStart[edgeFrom[e] + 1]++
            if (edgeTo[e] != edgeFrom[e]) nodeEdgeStart[edgeTo[e] + 1]++
        }
        for (n in 0 until nodeCount) nodeEdgeStart[n + 1] += nodeEdgeStart[n]
        nodeEdges = IntArray(nodeEdgeStart[nodeCount])
        val fill = nodeEdgeStart.copyOf(nodeCount)
        for (e in 0 until edgeCount) {
            nodeEdges[fill[edgeFrom[e]]++] = e
            if (edgeTo[e] != edgeFrom[e]) nodeEdges[fill[edgeTo[e]]++] = e
        }

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in vx.indices) {
            minX = min(minX, vx[i]); maxX = max(maxX, vx[i])
            minY = min(minY, vy[i]); maxY = max(maxY, vy[i])
        }
        if (vx.isEmpty()) { minX = 0f; minY = 0f; maxX = 0f; maxY = 0f }
        gridMinX = minX
        gridMinY = minY
        cols = ceil((maxX - minX) / cellSize).toInt() + 1
        rows = ceil((maxY - minY) / cellSize).toInt() + 1

        // Every street segment goes into each grid cell its bounding box touches (two passes: count, then fill).
        val counts = IntArray(cols * rows + 1)
        forEachSegmentCell { _, cell -> counts[cell + 1]++ }
        for (i in 0 until cols * rows) counts[i + 1] += counts[i]
        val items = IntArray(counts[cols * rows])
        val next = counts.copyOf(cols * rows)
        forEachSegmentCell { v, cell -> items[next[cell]++] = v }
        cellStart = counts
        cellItems = items
    }

    private inline fun forEachSegmentCell(block: (vertex: Int, cell: Int) -> Unit) {
        for (e in 0 until edgeCount) {
            for (v in edgeVertStart[e] until edgeVertStart[e + 1] - 1) {
                val c0 = cellX(min(vx[v], vx[v + 1])); val c1 = cellX(max(vx[v], vx[v + 1]))
                val r0 = cellY(min(vy[v], vy[v + 1])); val r1 = cellY(max(vy[v], vy[v + 1]))
                for (r in r0..r1) for (c in c0..c1) block(v, r * cols + c)
            }
        }
    }

    private fun cellX(x: Float) = ((x - gridMinX) / cellSize).toInt().coerceIn(0, cols - 1)
    private fun cellY(y: Float) = ((y - gridMinY) / cellSize).toInt().coerceIn(0, rows - 1)

    /** Calls [block] with the first vertex of every street segment that might lie within [radius] of the point. */
    inline fun forEachSegmentNear(x: Float, y: Float, radius: Float, block: (Int) -> Unit) {
        val c0 = cellIndexX(x - radius); val c1 = cellIndexX(x + radius)
        val r0 = cellIndexY(y - radius); val r1 = cellIndexY(y + radius)
        for (r in r0..r1) for (c in c0..c1) {
            for (i in cellRangeStart(r, c) until cellRangeEnd(r, c)) block(cellItem(i))
        }
    }

    // Public only so the inline function above can reach them.
    fun cellIndexX(x: Float) = cellX(x)
    fun cellIndexY(y: Float) = cellY(y)
    fun cellRangeStart(r: Int, c: Int) = cellStart[r * cols + c]
    fun cellRangeEnd(r: Int, c: Int) = cellStart[r * cols + c + 1]
    fun cellItem(i: Int) = cellItems[i]

    /** Calls [block] with each edge touching [node]. */
    inline fun forEachEdgeAt(node: Int, block: (Int) -> Unit) {
        for (i in nodeEdgeFirst(node) until nodeEdgeLast(node)) block(nodeEdgeAt(i))
    }

    fun nodeEdgeFirst(node: Int) = nodeEdgeStart[node]
    fun nodeEdgeLast(node: Int) = nodeEdgeStart[node + 1]
    fun nodeEdgeAt(i: Int) = nodeEdges[i]

    fun otherEnd(edge: Int, node: Int) = if (edgeFrom[edge] == node) edgeTo[edge] else edgeFrom[edge]

    /**
     * The part of [edge] between two distances from its start, as x,y pairs in travel order
     * (from [fromOffset] to [toOffset], so a reversed request comes back reversed).
     */
    fun slice(edge: Int, fromOffset: Float, toOffset: Float): List<Pair<Float, Float>> {
        val lo = min(fromOffset, toOffset).coerceIn(0f, edgeLength[edge])
        val hi = max(fromOffset, toOffset).coerceIn(0f, edgeLength[edge])
        val first = edgeVertStart[edge]
        val last = edgeVertStart[edge + 1] - 1
        val out = ArrayList<Pair<Float, Float>>()
        out.add(pointAt(edge, lo))
        for (v in first..last) if (cum[v] > lo + 1e-4f && cum[v] < hi - 1e-4f) out.add(vx[v] to vy[v])
        if (hi - lo > 1e-4f) out.add(pointAt(edge, hi))
        if (toOffset < fromOffset) out.reverse()
        return out
    }

    fun pointAt(edge: Int, offset: Float): Pair<Float, Float> {
        val first = edgeVertStart[edge]
        val last = edgeVertStart[edge + 1] - 1
        val s = offset.coerceIn(0f, edgeLength[edge])
        var lo = first
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= s) lo = mid else hi = mid
        }
        if (first == last) return vx[first] to vy[first]
        val span = cum[hi] - cum[lo]
        val t = if (span > 0f) (s - cum[lo]) / span else 0f
        return (vx[lo] + (vx[hi] - vx[lo]) * t) to (vy[lo] + (vy[hi] - vy[lo]) * t)
    }

    companion object {
        /** Reads the file written by data/build_road_graph.py. */
        fun parse(bytes: ByteArray): RoadGraph {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "HHRG") { "not a road graph file" }
            val version = buf.int
            require(version == 1) { "unsupported road graph version $version" }
            val nodes = buf.int
            val edges = buf.int
            val verts = buf.int
            val nameCount = buf.int

            fun ints(n: Int) = IntArray(n).also { buf.asIntBuffer().get(it); buf.position(buf.position() + 4 * n) }
            val from = ints(edges)
            val to = ints(edges)
            val vertStart = ints(edges + 1)
            val classes = ByteArray(edges).also { buf.get(it) }
            buf.position(buf.position() + (-edges).mod(4))
            val nameIdx = ints(edges)
            val lat = ints(verts)
            val lon = ints(verts)
            val names = List(nameCount) {
                val len = buf.short.toInt() and 0xFFFF
                String(ByteArray(len).also { b -> buf.get(b) }, Charsets.UTF_8)
            }
            val x = FloatArray(verts) { LocalFrame.x(lon[it] / 1e6) }
            val y = FloatArray(verts) { LocalFrame.y(lat[it] / 1e6) }
            return RoadGraph(nodes, from, to, vertStart, classes, nameIdx, x, y, names)
        }
    }
}
