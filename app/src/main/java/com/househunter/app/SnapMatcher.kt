package com.househunter.app

import java.util.PriorityQueue
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a rough finger drag into the streets it was tracing (requirements PEN-3 to PEN-5).
 *
 * Each finger sample is matched to the streets within [radius] meters. The matcher remembers where on the street
 * network it currently is, and prefers a street it can reach from there by a short route over a closer one it can't,
 * so a drag follows a street through curves and intersections instead of hopping to whichever line is nearest.
 * A finger that strays farther than [radius] from every street simply isn't traced.
 *
 * The result is the set of street pieces covered so far, not a path: retracing a street, or jittering back and
 * forth along it, never doubles anything up.
 */
class SnapMatcher(private val graph: RoadGraph, private val radius: Float) {
    private class Candidate(val edge: Int, val offset: Float, val distance: Float)

    /** Where the last matched sample landed, on the network. */
    private var edge = -1
    private var offset = 0f
    private var lastFingerX = 0f
    private var lastFingerY = 0f

    /** How far the finger has travelled since the stroke began; the start is the least certain part. */
    private var fingerTravel = 0f

    /** Covered stretches of each edge, as sorted, non-overlapping [from, to] offsets in meters. */
    private val covered = HashMap<Int, ArrayList<FloatArray>>()

    /** Bumped whenever the covered set grows, so callers can skip redrawing when nothing changed. */
    var version = 0
        private set

    val isEmpty get() = covered.isEmpty()

    /** Feed one finger position (meters, [LocalFrame]). Returns true if it extended the traced streets. */
    fun add(fx: Float, fy: Float): Boolean {
        val candidates = candidates(fx, fy)
        if (candidates.isEmpty()) return false

        if (edge < 0) {
            settle(candidates[0], fx, fy)
            return false
        }

        val move = hypot(fx - lastFingerX, fy - lastFingerY)
        fingerTravel += move
        // Near a junction several streets tie for nearest, so the first pick may be the wrong one. Until the finger
        // has gone a little way, switching streets costs nothing.
        val warmingUp = fingerTravel < WARM_UP_METERS
        search(edge, offset, 2 * move + 3 * radius + 40f)
        val allowedTravel = 1.5f * move + radius + 10f

        var best: Candidate? = null
        var bestRoute: Route? = null
        var bestCost = Float.MAX_VALUE
        for (c in candidates) {
            val route = routeTo(edge, offset, c)
            val connected = route != null && route.travel <= allowedTravel
            var cost = (c.distance / radius).let { it * it }
            if (!connected) cost += JUMP_COST
            else if (!warmingUp && c.edge != edge && !sameStreet(c.edge, edge)) cost += SWITCH_COST
            if (cost < bestCost) {
                bestCost = cost
                best = c
                bestRoute = if (connected) route else null
            }
        }
        val chosen = best ?: return false
        val changed = bestRoute?.let { cover(edge, offset, it, chosen) } ?: false
        settle(chosen, fx, fy)
        return changed
    }


    /**
     * Matches a whole finger trace at once (Viterbi). Unlike [add], which must decide as each point arrives, this
     * weighs every point against the ones that follow, so a brief excursion onto a side street the finger merely
     * crossed loses out to staying on the street it was following. Replaces anything traced so far.
     */
    fun matchAll(points: List<Pair<Float, Float>>) {
        covered.clear(); version++
        edge = -1
        // Thin the trace: far more points than the streets need only slows things down.
        val minGap = max(2.5f, radius / 4)
        val kept = ArrayList<Pair<Float, Float>>()
        for (p in points) {
            val last = kept.lastOrNull()
            if (last == null || hypot(p.first - last.first, p.second - last.second) >= minGap) kept.add(p)
        }
        if (points.isNotEmpty() && kept.last() != points.last()) kept.add(points.last())

        val xs = ArrayList<Pair<Float, Float>>()
        val cands = ArrayList<List<Candidate>>()
        for (p in kept) {
            val c = candidates(p.first, p.second)
            if (c.isNotEmpty()) { xs.add(p); cands.add(c) }
        }
        val n = xs.size
        if (n < 2) return

        val cost = Array(n) { FloatArray(cands[it].size) }
        val from = Array(n) { IntArray(cands[it].size) { -1 } }
        val linked = Array(n) { BooleanArray(cands[it].size) }
        for (k in cands[0].indices) cost[0][k] = emission(cands[0][k])
        for (i in 1 until n) {
            val move = hypot(xs[i].first - xs[i - 1].first, xs[i].second - xs[i - 1].second)
            val allowed = 1.5f * move + radius + 10f
            val limit = 2 * move + 3 * radius + 40f
            for (k in cands[i].indices) cost[i][k] = Float.MAX_VALUE
            for (j in cands[i - 1].indices) {
                val prev = cands[i - 1][j]
                search(prev.edge, prev.offset, limit)
                for (k in cands[i].indices) {
                    val c = cands[i][k]
                    val route = routeTo(prev.edge, prev.offset, c)
                    val connected = route != null && route.travel <= allowed
                    var step = emission(c)
                    if (!connected) {
                        // With the whole trace in view a hop between unconnected streets must clearly pay for itself.
                        step += TRACE_JUMP_COST
                    } else {
                        val excess = max(0f, route!!.travel - (1.3f * move + 4f))
                        step += DETOUR_WEIGHT * (excess / radius).let { it * it }
                        if (c.edge != prev.edge && !sameStreet(c.edge, prev.edge)) step += SWITCH_COST
                    }
                    val total = cost[i - 1][j] + step
                    if (total < cost[i][k]) { cost[i][k] = total; from[i][k] = j; linked[i][k] = connected }
                }
            }
        }

        val path = IntArray(n)
        path[n - 1] = cost[n - 1].indices.minByOrNull { cost[n - 1][it] } ?: return
        for (i in n - 1 downTo 1) path[i - 1] = from[i][path[i]]
        for (i in 1 until n) {
            if (!linked[i][path[i]]) continue
            val prev = cands[i - 1][path[i - 1]]
            val c = cands[i][path[i]]
            val move = hypot(xs[i].first - xs[i - 1].first, xs[i].second - xs[i - 1].second)
            search(prev.edge, prev.offset, 2 * move + 3 * radius + 40f)
            routeTo(prev.edge, prev.offset, c)?.let { cover(prev.edge, prev.offset, it, c) }
        }
    }

    private fun emission(c: Candidate) = (c.distance / radius).let { it * it }

    /** The traced streets as polylines of x,y pairs (meters). Chains run through junctions where they can. */
    fun polylines(): List<List<Pair<Float, Float>>> {
        val pieces = ArrayList<Piece>()
        var freeKey = -1
        for ((e, list) in covered) {
            val len = graph.edgeLength[e]
            for (iv in list) {
                val a = iv[0]
                val b = iv[1]
                if (b - a < 0.05f) continue
                val keyA = if (a <= END_SLACK) graph.edgeFrom[e] else freeKey--
                val keyB = if (b >= len - END_SLACK) graph.edgeTo[e] else freeKey--
                pieces.add(Piece(e, a, b, keyA, keyB))
            }
        }
        val alive = pruneHairs(pieces)

        val touching = HashMap<Int, ArrayList<Int>>()
        for (i in alive) {
            touching.getOrPut(pieces[i].keyA) { ArrayList() }.add(i)
            touching.getOrPut(pieces[i].keyB) { ArrayList() }.add(i)
        }
        val used = HashSet<Int>()
        val out = ArrayList<List<Pair<Float, Float>>>()
        for (start in alive) {
            if (!used.add(start)) continue
            val line = ArrayDeque<Pair<Float, Float>>()
            line.addAll(pieceGeometry(pieces[start], forward = true))
            // Grow at the end, then at the start, through any junction where exactly two pieces meet.
            var key = pieces[start].keyB
            while (true) {
                val next = neighbourAt(touching, key, used, pieces) ?: break
                val forward = pieces[next].keyA == key
                val geom = pieceGeometry(pieces[next], forward)
                line.addAll(geom.drop(1))
                key = if (forward) pieces[next].keyB else pieces[next].keyA
                used.add(next)
            }
            key = pieces[start].keyA
            while (true) {
                val next = neighbourAt(touching, key, used, pieces) ?: break
                val forward = pieces[next].keyB == key // arrives at the shared key, so walk it toward the key
                val geom = pieceGeometry(pieces[next], forward)
                geom.dropLast(1).asReversed().forEach { line.addFirst(it) }
                key = if (forward) pieces[next].keyA else pieces[next].keyB
                used.add(next)
            }
            out.add(line.toList())
        }
        return out
    }

    // ---- candidates ---------------------------------------------------------------------------------------

    private fun candidates(fx: Float, fy: Float): List<Candidate> {
        val best = HashMap<Int, Candidate>()
        graph.forEachSegmentNear(fx, fy, radius) { v ->
            val ax = graph.vx[v]; val ay = graph.vy[v]
            val dx = graph.vx[v + 1] - ax; val dy = graph.vy[v + 1] - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0f) (((fx - ax) * dx + (fy - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
            val distance = hypot(fx - (ax + t * dx), fy - (ay + t * dy))
            if (distance <= radius) {
                val e = graph.vertEdge[v]
                val old = best[e]
                if (old == null || distance < old.distance) {
                    best[e] = Candidate(e, graph.cum[v] + t * sqrt(len2), distance)
                }
            }
        }
        return best.values.sortedBy { it.distance }.take(MAX_CANDIDATES)
    }

    private fun settle(c: Candidate, fx: Float, fy: Float) {
        edge = c.edge
        offset = c.offset
        lastFingerX = fx
        lastFingerY = fy
    }

    private fun sameStreet(a: Int, b: Int) = graph.edgeName[a] >= 0 && graph.edgeName[a] == graph.edgeName[b]

    // ---- routing over the network -------------------------------------------------------------------------

    private class Route(val travel: Float, val sameEdge: Boolean, val entryNode: Int)

    private val dist = HashMap<Int, Float>()
    private val cameBy = HashMap<Int, Int>()      // node -> edge used to reach it (-1 for the two starting nodes)
    private val cameFrom = HashMap<Int, Int>()    // node -> previous node

    private class QueueItem(val node: Int, val dist: Float)

    /** Shortest distances from the current position to every junction within [limit] meters. */
    private fun search(fromEdge: Int, fromOffset: Float, limit: Float) {
        dist.clear(); cameBy.clear(); cameFrom.clear()
        val queue = PriorityQueue<QueueItem>(compareBy { it.dist })
        val len = graph.edgeLength[fromEdge]
        for ((node, d) in listOf(graph.edgeFrom[fromEdge] to fromOffset, graph.edgeTo[fromEdge] to len - fromOffset)) {
            if (d < (dist[node] ?: Float.MAX_VALUE)) {
                dist[node] = d
                cameBy[node] = -1
                queue.add(QueueItem(node, d))
            }
        }
        while (queue.isNotEmpty()) {
            val item = queue.poll()
            if (item.dist > (dist[item.node] ?: Float.MAX_VALUE) || item.dist > limit) continue
            graph.forEachEdgeAt(item.node) { e ->
                val next = graph.otherEnd(e, item.node)
                val nd = item.dist + graph.edgeLength[e]
                if (nd <= limit && nd < (dist[next] ?: Float.MAX_VALUE)) {
                    dist[next] = nd
                    cameBy[next] = e
                    cameFrom[next] = item.node
                    queue.add(QueueItem(next, nd))
                }
            }
        }
    }

    private fun routeTo(fromEdge: Int, fromOffset: Float, c: Candidate): Route? {
        if (c.edge == fromEdge) return Route(kotlin.math.abs(c.offset - fromOffset), sameEdge = true, entryNode = -1)
        val viaFrom = dist[graph.edgeFrom[c.edge]]?.let { it + c.offset }
        val viaTo = dist[graph.edgeTo[c.edge]]?.let { it + (graph.edgeLength[c.edge] - c.offset) }
        return when {
            viaFrom == null && viaTo == null -> null
            viaTo == null || (viaFrom != null && viaFrom <= viaTo) -> Route(viaFrom!!, false, graph.edgeFrom[c.edge])
            else -> Route(viaTo, false, graph.edgeTo[c.edge])
        }
    }

    /** Marks every street piece between the current position and the candidate as traced. */
    private fun cover(fromEdge: Int, fromOffset: Float, route: Route, c: Candidate): Boolean {
        if (route.sameEdge) return addInterval(fromEdge, fromOffset, c.offset)
        var changed = false
        // Walk back from the entry node to whichever end of the starting edge the route left from.
        var node = route.entryNode
        val entryEdge = c.edge
        val entryOffset = if (node == graph.edgeFrom[entryEdge]) 0f else graph.edgeLength[entryEdge]
        while (true) {
            val by = cameBy[node] ?: break
            if (by == -1) {
                val startOffset = if (node == graph.edgeFrom[fromEdge]) 0f else graph.edgeLength[fromEdge]
                changed = addInterval(fromEdge, fromOffset, startOffset) or changed
                break
            }
            changed = addInterval(by, 0f, graph.edgeLength[by]) or changed
            node = cameFrom[node] ?: break
        }
        return addInterval(entryEdge, entryOffset, c.offset) or changed
    }

    private fun addInterval(e: Int, a: Float, b: Float): Boolean {
        var lo = min(a, b)
        var hi = max(a, b)
        if (hi - lo < 0.01f) return false
        val list = covered.getOrPut(e) { ArrayList() }
        var grew = true
        val merged = ArrayList<FloatArray>(list.size + 1)
        for (iv in list) {
            if (iv[1] < lo - 0.05f || iv[0] > hi + 0.05f) {
                merged.add(iv)
            } else {
                if (iv[0] <= lo && iv[1] >= hi) grew = false
                lo = min(lo, iv[0])
                hi = max(hi, iv[1])
            }
        }
        if (!grew) return false
        merged.add(floatArrayOf(lo, hi))
        merged.sortBy { it[0] }
        list.clear()
        list.addAll(merged)
        version++
        return true
    }

    // ---- turning coverage into lines ----------------------------------------------------------------------

    private class Piece(val edge: Int, val from: Float, val to: Float, val keyA: Int, val keyB: Int) {
        val length get() = to - from
    }

    private fun pieceGeometry(p: Piece, forward: Boolean) =
        if (forward) graph.slice(p.edge, p.from, p.to) else graph.slice(p.edge, p.to, p.from)

    /**
     * Drops tiny dangling stubs: a short piece with a loose end hanging off a junction where the trace continues.
     * They come from a wobbly finger brushing a cross street as it passes.
     */
    private fun pruneHairs(pieces: List<Piece>): List<Int> {
        val degree = HashMap<Int, Int>()
        for (p in pieces) {
            degree[p.keyA] = (degree[p.keyA] ?: 0) + 1
            degree[p.keyB] = (degree[p.keyB] ?: 0) + 1
        }
        return pieces.indices.filter { i ->
            val p = pieces[i]
            val looseA = p.keyA < 0
            val looseB = p.keyB < 0
            val hair = p.length < HAIR_LENGTH && (looseA != looseB) &&
                (degree[if (looseA) p.keyB else p.keyA] ?: 0) >= 3
            !hair
        }
    }

    /** The one other unused piece meeting [key], if exactly two pieces meet there. */
    private fun neighbourAt(touching: Map<Int, List<Int>>, key: Int, used: Set<Int>, pieces: List<Piece>): Int? {
        if (key < 0) return null
        val here = touching[key] ?: return null
        if (here.size != 2) return null
        return here.firstOrNull { it !in used }
    }

    private companion object {
        const val JUMP_COST = 1.0f
        const val SWITCH_COST = 0.25f
        const val DETOUR_WEIGHT = 3f
        const val TRACE_JUMP_COST = 3f
        const val MAX_CANDIDATES = 6
        const val WARM_UP_METERS = 40f
        const val END_SLACK = 0.5f
        const val HAIR_LENGTH = 9f
    }
}
