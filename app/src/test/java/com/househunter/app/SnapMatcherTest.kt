package com.househunter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private typealias P = Pair<Float, Float>

class SnapMatcherTest {
    private class E(val from: Int, val to: Int, val name: Int, val pts: List<P>)

    private fun graphOf(nodes: Int, edges: List<E>, names: List<String> = emptyList()): RoadGraph {
        val start = IntArray(edges.size + 1)
        edges.forEachIndexed { i, e -> start[i + 1] = start[i] + e.pts.size }
        return RoadGraph(
            nodes, IntArray(edges.size) { edges[it].from }, IntArray(edges.size) { edges[it].to }, start,
            ByteArray(edges.size), IntArray(edges.size) { edges[it].name },
            FloatArray(start.last()) { i -> edges.flatMap { it.pts }[i].first },
            FloatArray(start.last()) { i -> edges.flatMap { it.pts }[i].second },
            names,
        )
    }

    /** A square grid of straight one-block streets, [n] x [n] junctions, 100 m apart; junction (i,j) is at (100i, 100j). */
    private fun grid(n: Int = 5): RoadGraph {
        val edges = ArrayList<E>()
        for (j in 0 until n) for (i in 0 until n) {
            if (i + 1 < n) edges.add(E(j * n + i, j * n + i + 1, j, listOf(100f * i to 100f * j, 100f * (i + 1) to 100f * j)))
            if (j + 1 < n) edges.add(E(j * n + i, (j + 1) * n + i, n + i, listOf(100f * i to 100f * j, 100f * i to 100f * (j + 1))))
        }
        return graphOf(n * n, edges, List(2 * n) { "Street $it" })
    }

    private fun run(g: RoadGraph, points: List<P>, radius: Float = 30f): SnapMatcher =
        SnapMatcher(g, radius).also { m -> points.forEach { m.add(it.first, it.second) } }

    /** The whole-stroke pass the app runs when the finger lifts. */
    private fun runOffline(g: RoadGraph, points: List<P>, radius: Float = 30f): SnapMatcher =
        SnapMatcher(g, radius).also { it.matchAll(points) }

    private fun length(line: List<P>) = line.zipWithNext().sumOf { (a, b) -> hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) }

    @Test fun `a jittery drag along a street snaps onto its centre line`() {
        val rnd = Random(1)
        val drag = (0..76).map { i -> (10f + i * 5f) to (rnd.nextFloat() * 20f - 10f) }
        val lines = run(grid(), drag).polylines()
        assertEquals(1, lines.size)
        assertTrue("every point sits on the street", lines[0].all { abs(it.second) < 0.01f })
        assertEquals(10.0, lines[0].first().first.toDouble(), 6.0)
        assertEquals(390.0, lines[0].last().first.toDouble(), 6.0)
    }

    @Test fun `cutting a corner still goes through the junction`() {
        val drag = listOf(20f to 5f, 50f to 6f, 80f to 8f, 92f to 20f, 97f to 40f, 99f to 60f, 100f to 80f)
        val lines = run(grid(), drag).polylines()
        assertEquals(1, lines.size)
        assertTrue("passes through the corner at (100, 0)", lines[0].any { hypot(it.first - 100f, it.second) < 0.01f })
        assertTrue("ends up on the cross street", abs(lines[0].last().first - 100f) < 0.01f)
    }

    @Test fun `driving straight through an intersection does not wander into the cross street`() {
        val rnd = Random(7)
        val drag = (0..70).map { i -> (20f + i * 5f) to (rnd.nextFloat() * 16f - 8f) }
        val lines = run(grid(), drag).polylines()
        assertEquals(1, lines.size)
        assertTrue("stays on y = 0", lines[0].all { abs(it.second) < 0.01f })
    }

    @Test fun `a drag held far to one side does not grow stubs onto the side streets it crosses`() {
        // 25 m beside the street (the snap radius is 30): the finger crosses each side street 25 m from the main one.
        val rnd = Random(2)
        val drag = (0..72).map { i -> (20f + i * 5f) to (25f + rnd.nextFloat() * 2f - 1f) }
        val lines = runOffline(grid(), drag).polylines()
        assertEquals(1, lines.size)
        assertTrue("only the street it was following", lines[0].all { abs(it.second) < 0.01f })
    }

    @Test fun `a finger that is nowhere near a street traces nothing`() {
        val m = run(grid(), listOf(50f to 50f, 60f to 50f, 70f to 50f))
        assertTrue(m.polylines().isEmpty())
        assertFalse(m.add(80f, 50f))
    }

    @Test fun `hopping to an unconnected street starts a separate line`() {
        val a = E(0, 1, 0, listOf(0f to 0f, 400f to 0f))
        val b = E(2, 3, 1, listOf(0f to 40f, 400f to 40f))
        val g = graphOf(4, listOf(a, b))
        val drag = (0..20).map { (20f + it * 5f) to 2f } + (0..20).map { (200f + it * 5f) to 38f }
        val lines = run(g, drag).polylines()
        assertEquals(2, lines.size)
    }

    @Test fun `a curved street is followed vertex by vertex`() {
        val arc = (0..20).map { k -> 200f * cos(Math.PI * k / 40).toFloat() to 200f * sin(Math.PI * k / 40).toFloat() }
        val g = graphOf(2, listOf(E(0, 1, 0, arc)))
        val rnd = Random(3)
        val drag = arc.dropLast(1).zipWithNext().flatMap { (a, b) ->
            (0 until 4).map { t -> (a.first + (b.first - a.first) * t / 4 + rnd.nextFloat() * 8 - 4) to (a.second + (b.second - a.second) * t / 4 + rnd.nextFloat() * 8 - 4) }
        }
        val lines = run(g, drag, radius = 25f).polylines()
        assertEquals(1, lines.size)
        assertTrue("stays on the street's own geometry", lines[0].all { distanceToPolyline(it, arc) < 0.05 })
        assertTrue("covers most of it", length(lines[0]) > 0.9 * length(arc))
    }

    @Test fun `going back and forth over the same stretch never doubles it`() {
        val there = (0..30).map { (20f + it * 5f) to 3f }
        val back = there.reversed()
        val lines = run(grid(), there + back + there).polylines()
        assertEquals(1, lines.size)
        assertEquals(150.0, length(lines[0]), 6.0)
    }

    @Test fun `the traced route follows the network when the finger leaps across a block`() {
        // Finger jumps from (200,0) to (250,100): the network route goes up the street at x = 200.
        val drag = (0..8).map { (120f + it * 10f) to 4f } + listOf(250f to 96f, 260f to 100f, 280f to 100f)
        val lines = run(grid(), drag).polylines()
        assertEquals(1, lines.size)
        assertTrue("passes through junction (200, 0)", lines[0].any { hypot(it.first - 200f, it.second) < 0.01f })
        assertTrue("goes up the street x = 200", lines[0].any { abs(it.first - 200f) < 0.01f && it.second > 50f })
    }

    @Test fun `snapping follows a real street in Atlanta`() {
        val file = File("../data/assets/road_graph.bin")
        assumeTrue("road_graph.bin not built", file.exists())
        val g = RoadGraph.parse(file.readBytes())
        val chain = longestSameNameChain(g) ?: return
        val truth = chain.second
        val rnd = Random(11)
        // A hand-drawn drag: a sample every 6 m, drifting up to ~10 m off the street.
        val finger = ArrayList<P>()
        var drift = 0f
        var walked = 0.0
        var seg = 0
        var segStart = 0.0
        while (seg < truth.size - 1) {
            val (a, b) = truth[seg] to truth[seg + 1]
            val segLen = hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble())
            if (walked - segStart > segLen) { segStart += segLen; seg++; continue }
            val t = ((walked - segStart) / segLen).toFloat()
            val nx = -(b.second - a.second) / segLen.toFloat()
            val ny = (b.first - a.first) / segLen.toFloat()
            drift = 0.9f * drift + 0.1f * (rnd.nextFloat() * 2 - 1) * 12f
            finger.add((a.first + (b.first - a.first) * t + nx * drift) to (a.second + (b.second - a.second) * t + ny * drift))
            walked += 6.0
        }
        val t0 = System.nanoTime()
        SnapMatcher(g, 30f).matchAll(finger)
        println("whole-stroke pass over a ${"%.1f".format(length(truth) / 1000)} km, ${finger.size}-sample stroke took ${(System.nanoTime() - t0) / 1_000_000} ms (JVM)")
        val lines = run(g, finger, radius = 30f).polylines()
        val traced = lines.sumOf { length(it) }
        val onStreet = lines.sumOf { line -> line.zipWithNext().sumOf { (a, b) ->
            val mid = ((a.first + b.first) / 2) to ((a.second + b.second) / 2)
            if (distanceToPolyline(mid, truth) < 2.0) hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) else 0.0
        } }
        println("real street: true length ${"%.0f".format(length(truth))} m, traced ${"%.0f".format(traced)} m, on the street ${"%.0f".format(onStreet)} m, ${lines.size} line(s)")
        assertTrue("traced most of the street", traced > 0.85 * length(truth))
        assertTrue("and nearly all of it is on the right street", onStreet > 0.92 * traced)
    }

    @Test fun `random routes with turns across real Atlanta stay on route`() {
        val file = File("../data/assets/road_graph.bin")
        assumeTrue("road_graph.bin not built", file.exists())
        val g = RoadGraph.parse(file.readBytes())
        val rnd = Random(5)
        var sumTrue = 0.0; var sumTraced = 0.0; var sumOn = 0.0
        var worst = 1.0
        var runs = 0
        while (runs < 25) {
            val route = randomRoute(g, rnd, 1200.0) ?: continue
            val finger = fingerAlong(route, rnd, drift = 10f)
            val lines = run(g, finger, radius = 30f).polylines()
            val traced = lines.sumOf { length(it) }
            val on = lines.sumOf { line -> line.zipWithNext().sumOf { (a, b) ->
                val mid = ((a.first + b.first) / 2) to ((a.second + b.second) / 2)
                if (distanceToPolyline(mid, route) < 2.0) hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) else 0.0
            } }
            sumTrue += length(route); sumTraced += traced; sumOn += on
            worst = minOf(worst, on / traced.coerceAtLeast(1.0))
            runs++
        }
        println("25 random routes: traced ${"%.0f".format(100 * sumTraced / sumTrue)}% of the true length; " +
            "${"%.1f".format(100 * sumOn / sumTraced)}% of what was traced is on the route; worst route ${"%.0f".format(100 * worst)}% on-route")
        assertTrue("traces most of each route", sumTraced > 0.85 * sumTrue)
        assertTrue("and stays on the route", sumOn > 0.90 * sumTraced)
    }

    /** A drag along [route]: a sample every 6 m, wandering up to about [drift] meters to either side. */
    private fun fingerAlong(route: List<P>, rnd: Random, drift: Float): List<P> {
        val out = ArrayList<P>()
        var wander = 0f
        var walked = 0.0
        var seg = 0
        var segStart = 0.0
        while (seg < route.size - 1) {
            val (a, b) = route[seg] to route[seg + 1]
            val segLen = hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble())
            if (segLen < 1e-6 || walked - segStart > segLen) { segStart += segLen; seg++; continue }
            val t = ((walked - segStart) / segLen).toFloat()
            val nx = -(b.second - a.second) / segLen.toFloat()
            val ny = (b.first - a.first) / segLen.toFloat()
            wander = 0.9f * wander + 0.1f * (rnd.nextFloat() * 2 - 1) * drift * 1.2f
            out.add((a.first + (b.first - a.first) * t + nx * wander) to (a.second + (b.second - a.second) * t + ny * wander))
            walked += 6.0
        }
        return out
    }

    /** A random drive: from a random street, mostly straight on at junctions but sometimes turning. */
    private fun randomRoute(g: RoadGraph, rnd: Random, targetLength: Double): List<P>? {
        var edge = rnd.nextInt(g.edgeCount)
        if (g.edgeLength[edge] < 40f) return null
        val pts = ArrayList(g.slice(edge, 0f, g.edgeLength[edge]))
        var node = g.edgeTo[edge]
        var total = g.edgeLength[edge].toDouble()
        while (total < targetLength) {
            val heading = pts[pts.size - 1].first - pts[pts.size - 2].first to pts[pts.size - 1].second - pts[pts.size - 2].second
            val options = ArrayList<Pair<Int, Double>>()
            g.forEachEdgeAt(node) { e ->
                if (e != edge && g.edgeLength[e] > 5f) {
                    val forward = g.edgeFrom[e] == node
                    val geom = if (forward) g.slice(e, 0f, 3f) else g.slice(e, g.edgeLength[e], g.edgeLength[e] - 3f)
                    val dx = geom.last().first - geom.first().first
                    val dy = geom.last().second - geom.first().second
                    val dot = (dx * heading.first + dy * heading.second) / (hypot(dx, dy) * hypot(heading.first, heading.second) + 1e-6f)
                    options.add(e to Math.exp(2.5 * dot))
                }
            }
            if (options.isEmpty()) return if (total > 300) pts else null
            var pick = rnd.nextDouble() * options.sumOf { it.second }
            var chosen = options.last().first
            for ((e, w) in options) { pick -= w; if (pick <= 0) { chosen = e; break } }
            val forward = g.edgeFrom[chosen] == node
            val geom = if (forward) g.slice(chosen, 0f, g.edgeLength[chosen]) else g.slice(chosen, g.edgeLength[chosen], 0f)
            pts.addAll(geom.drop(1))
            total += g.edgeLength[chosen]
            node = g.otherEnd(chosen, node)
            edge = chosen
        }
        return pts
    }

    @Test fun `almost none of a random route goes untraced`() {
        val file = File("../data/assets/road_graph.bin")
        assumeTrue("road_graph.bin not built", file.exists())
        val g = RoadGraph.parse(file.readBytes())
        val rnd = Random(5)
        var runs = 0
        var startGap = 0.0; var endGap = 0.0; var midGap = 0.0; var total = 0.0
        val midGaps = ArrayList<String>()
        while (runs < 25) {
            val route = randomRoute(g, rnd, 1200.0) ?: continue
            val finger = fingerAlong(route, rnd, drift = 10f)
            val lines = run(g, finger, radius = 30f).polylines()
            // Walk the true route in 2 m steps; a step is "missed" if no traced point is within 3 m.
            val traced = lines.flatten()
            fun near(p: P) = lines.any { distanceToPolyline(p, it) < 3.0 }
            val n = (length(route) / 2).toInt()
            val missed = BooleanArray(n) { i ->
                var d = i * 2.0; var pt = route.last()
                var k = 0
                while (k < route.size - 1) { val sl = hypot((route[k+1].first-route[k].first).toDouble(), (route[k+1].second-route[k].second).toDouble()); if (d <= sl) { val t = (d/sl).toFloat(); pt = (route[k].first+(route[k+1].first-route[k].first)*t) to (route[k].second+(route[k+1].second-route[k].second)*t); break }; d -= sl; k++ }
                !near(pt)
            }
            total += n * 2.0
            var i = 0
            while (i < n) {
                if (!missed[i]) { i++; continue }
                var j = i; while (j < n && missed[j]) j++
                val len = (j - i) * 2.0
                when { i == 0 -> startGap += len; j == n -> endGap += len; else -> { midGap += len; midGaps.add("${"%.0f".format(len)}m@${"%.0f".format(i*2.0)}") } }
                i = j
            }
            runs++
        }
        assertTrue("almost nothing along the routes goes untraced", midGap + startGap + endGap < 0.01 * total)
        println("untraced: start ${"%.0f".format(startGap)} m, end ${"%.0f".format(endGap)} m, interior ${"%.0f".format(midGap)} m of ${"%.0f".format(total)} m; interior gaps: ${midGaps.take(20)}")
    }

    @Test fun `accuracy holds up as the finger drifts further from the street`() {
        val file = File("../data/assets/road_graph.bin")
        assumeTrue("road_graph.bin not built", file.exists())
        val g = RoadGraph.parse(file.readBytes())
        for (offline in listOf(false, true)) for (drift in listOf(8f, 14f, 20f, 26f)) {
            val rnd = Random(9)
            var sumTraced = 0.0; var sumOn = 0.0; var covered = 0.0; var routeLen = 0.0; var runs = 0
            while (runs < 30) {
                val route = randomRoute(g, rnd, 1200.0) ?: continue
                val finger = fingerAlong(route, rnd, drift)
                val lines = (if (offline) runOffline(g, finger, 30f) else run(g, finger, radius = 30f)).polylines()
                val traced = lines.sumOf { length(it) }
                val on = lines.sumOf { line -> line.zipWithNext().sumOf { (a, b) ->
                    val mid = ((a.first + b.first) / 2) to ((a.second + b.second) / 2)
                    if (distanceToPolyline(mid, route) < 2.0) hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()) else 0.0
                } }
                // Recall: how much of the route (in 4 m steps) has traced geometry on it.
                var hit = 0; var n = 0
                var d = 0.0; var k = 0
                val total = length(route)
                while (d < total && k < route.size - 1) {
                    val sl = hypot((route[k+1].first-route[k].first).toDouble(), (route[k+1].second-route[k].second).toDouble())
                    if (sl < 1e-6) { k++; continue }
                    var pos = 0.0
                    while (pos < sl) { val t = (pos / sl).toFloat(); val pt = (route[k].first+(route[k+1].first-route[k].first)*t) to (route[k].second+(route[k+1].second-route[k].second)*t); n++; if (lines.any { distanceToPolyline(pt, it) < 3.0 }) hit++; pos += 4.0 }
                    d += sl; k++
                }
                sumTraced += traced; sumOn += on; covered += hit; routeLen += n
                runs++
            }
            println("${if (offline) "whole-stroke" else "live       "} drift +-${drift.toInt()} m (radius 30): ${"%.1f".format(100 * sumOn / sumTraced)}% of the traced line is on the route, ${"%.1f".format(100 * covered / routeLen)}% of the route is traced")
            if (offline) {
                assertTrue("whole-stroke precision at drift $drift", sumOn / sumTraced > 0.985)
                assertTrue("whole-stroke recall at drift $drift", covered / routeLen > 0.985)
            }
        }
    }

    @Test fun `a far-off drag beside a real street, even a sparse one, traces the whole street`() {
        val file = File("../data/assets/road_graph.bin")
        assumeTrue("road_graph.bin not built", file.exists())
        val g = RoadGraph.parse(file.readBytes())
        // Screen centre (540,1200) px = (33.7825, -84.3830) at zoom 15.6; 1 px ~ 0.479 m; screen y grows southward.
        val cx = LocalFrame.x(-84.3830); val cy = LocalFrame.y(33.7825)
        fun at(px: Float, py: Float) = (cx + (px - 540f) * 0.479f) to (cy - (py - 1200f) * 0.479f)
        val steps = 74
        val finger = (0..steps).map { i -> at(705f + (668f - 705f) * i / steps, 560f + (1300f - 560f) * i / steps) }
        // The emulator only delivered ~14 touch points for this swipe, so also try a sparse trace.
        for (n in listOf(14, 8, 5)) {
            val sparse = (0..n - 1).map { i -> at(705f + (668f - 705f) * i / (n - 1), 560f + (1300f - 560f) * i / (n - 1)) }
            val off = runOffline(g, sparse, 36.6f).polylines(); val on = run(g, sparse, 36.6f).polylines()
            println("radius sparse $n pts: whole-stroke ${off.map { "%.0f".format(length(it)) }} live ${on.map { "%.0f".format(length(it)) }} (finger ${"%.0f".format(length(sparse))} m)")
            assertEquals("a sparse $n-point trace still gives one line", 1, off.size)
            assertTrue(length(off[0]) > 0.95 * length(sparse))
        }
        for (radius in listOf(35f, 28f)) {
            val lines = runOffline(g, finger, radius).polylines()
            println("radius $radius: ${lines.size} line(s), lengths ${lines.map { "%.0f".format(length(it)) }}; finger path ${"%.0f".format(length(finger))} m")
            val live = run(g, finger, radius).polylines()
            println("  live: ${live.size} line(s), lengths ${live.map { "%.0f".format(length(it)) }}")
            assertEquals(1, lines.size)
            assertTrue("traces the whole drag", length(lines[0]) > 0.95 * length(finger))
        }
    }

    // ---- helpers for the real-data test --------------------------------------------------------------------

    /** Walks along one named street, always taking the straightest same-named continuation; returns name and x,y points. */
    private fun longestSameNameChain(g: RoadGraph): Pair<String, List<P>>? {
        var best: Pair<String, List<P>>? = null
        for (wanted in listOf("Peachtree Street Northeast", "Ponce de Leon Avenue Northeast", "North Highland Avenue Northeast", "Piedmont Avenue Northeast")) {
            val nameIdx = g.names.indexOf(wanted)
            if (nameIdx < 0) continue
            val start = (0 until g.edgeCount).filter { g.edgeName[it] == nameIdx }.maxByOrNull { g.edgeLength[it] } ?: continue
            val pts = ArrayList<P>()
            val seen = HashSet<Int>()
            var edge = start
            var node = g.edgeTo[start]
            pts.addAll(g.slice(start, 0f, g.edgeLength[start]))
            seen.add(start)
            while (seen.size < 40) {
                val incoming = pts[pts.size - 1].first - pts[pts.size - 2].first to pts[pts.size - 1].second - pts[pts.size - 2].second
                var next = -1
                var bestDot = -2f
                g.forEachEdgeAt(node) { e ->
                    if (e != edge && e !in seen && g.edgeName[e] == nameIdx) {
                        val forward = g.edgeFrom[e] == node
                        val geom = if (forward) g.slice(e, 0f, 0.5f) else g.slice(e, g.edgeLength[e], g.edgeLength[e] - 0.5f)
                        val dx = geom.last().first - geom.first().first
                        val dy = geom.last().second - geom.first().second
                        val dot = (dx * incoming.first + dy * incoming.second) / (hypot(dx, dy) * hypot(incoming.first, incoming.second) + 1e-6f)
                        if (dot > bestDot) { bestDot = dot; next = e }
                    }
                }
                if (next < 0 || bestDot < 0.5f) break
                val forward = g.edgeFrom[next] == node
                val geom = if (forward) g.slice(next, 0f, g.edgeLength[next]) else g.slice(next, g.edgeLength[next], 0f)
                pts.addAll(geom.drop(1))
                seen.add(next)
                node = g.otherEnd(next, node)
                edge = next
            }
            if (best == null || length(pts) > length(best.second)) best = wanted to pts
        }
        return best
    }

    private fun distanceToPolyline(p: P, line: List<P>): Double = line.zipWithNext().minOf { (a, b) ->
        val dx = (b.first - a.first).toDouble(); val dy = (b.second - a.second).toDouble()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.first - a.first) * dx + (p.second - a.second) * dy) / len2).coerceIn(0.0, 1.0)
        hypot(p.first - (a.first + t * dx), p.second - (a.second + t * dy))
    }
}
