package com.househunter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeEraserTest {
    // A straight east-west line at Atlanta's latitude, ~1 km long, points every ~100 m.
    private val lat = 33.78
    private val lon0 = -84.40
    private val degPer100m = 100.0 / (111_320.0 * Math.cos(Math.toRadians(lat)))
    private fun pointAt(meters: Double) = LatLon(lat, lon0 + meters / 100.0 * degPer100m)
    private val line = Stroke(id = "s", color = 0, parts = listOf((0..10).map { pointAt(it * 100.0) }))
    private fun eraseAt(meters: Double, r: Double, strokes: List<Stroke> = listOf(line)) =
        StrokeEraser.erase(strokes, pointAt(meters), r)

    @Test fun `eraser far from the stroke changes nothing`() {
        val strokes = listOf(line)
        assertSame(strokes, StrokeEraser.erase(strokes, LatLon(lat + 0.01, lon0), 30.0))
    }

    @Test fun `eraser in the middle splits a stroke into two pieces`() {
        val result = eraseAt(500.0, 30.0)
        assertEquals(1, result.size)
        val parts = result[0].parts
        assertEquals(2, parts.size)
        // Pieces end ~30 m either side of the eraser centre (500 m).
        assertEquals(0.0, metersFromStart(parts[0].first()), 0.5)
        assertEquals(470.0, metersFromStart(parts[0].last()), 1.0)
        assertEquals(530.0, metersFromStart(parts[1].first()), 1.0)
        assertEquals(1000.0, metersFromStart(parts[1].last()), 0.5)
    }

    @Test fun `eraser at the end trims the stroke instead of splitting it`() {
        val result = eraseAt(0.0, 30.0)
        assertEquals(1, result[0].parts.size)
        assertEquals(30.0, metersFromStart(result[0].parts[0].first()), 1.0)
    }

    @Test fun `eraser bigger than the stroke removes it entirely`() {
        assertTrue(eraseAt(500.0, 2000.0).isEmpty())
    }

    @Test fun `eraser hits a long segment even with no vertex nearby`() {
        // Only two vertices 1 km apart; the eraser sits mid-segment, far from both.
        val sparse = Stroke(id = "sparse", color = 1, parts = listOf(listOf(pointAt(0.0), pointAt(1000.0))))
        val result = eraseAt(500.0, 20.0, listOf(sparse))
        assertEquals(2, result[0].parts.size)
    }

    @Test fun `only the touched stroke is modified`() {
        val other = Stroke(id = "o", color = 2, parts = listOf(listOf(LatLon(lat + 0.01, lon0), LatLon(lat + 0.01, lon0 + 0.01))))
        val result = eraseAt(500.0, 30.0, listOf(line, other))
        assertEquals(2, result.size)
        assertNotSame(line, result[0])
        assertSame(other, result[1])
    }

    @Test fun `simplify drops points on a straight line but keeps corners`() {
        val pts = listOf(
            PathSimplifier.Pt(0.0, 0.0), PathSimplifier.Pt(5.0, 0.1), PathSimplifier.Pt(10.0, 0.0),
            PathSimplifier.Pt(10.0, 10.0),
        )
        val simplified = PathSimplifier.simplify(pts, 1.0)
        assertEquals(listOf(pts[0], pts[2], pts[3]), simplified)
    }

    private fun metersFromStart(p: LatLon) = (p.lon - lon0) / degPer100m * 100.0
}
