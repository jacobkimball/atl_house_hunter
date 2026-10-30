package com.househunter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingLayersTest {
    private fun stroke(id: String, layer: String) = Stroke(id = id, color = 0, parts = listOf(listOf(LatLon(33.0, -84.0), LatLon(33.1, -84.1))), layerId = layer)

    @Test fun `there is always a first layer`() {
        assertEquals(listOf(DrawingLayer("layer-1", "Layer 1")), DrawingLayers.initial())
    }

    @Test fun `layers can be added up to the limit of four, then no more`() {
        var layers = DrawingLayers.initial()
        repeat(3) { layers = DrawingLayers.add(layers)!! }
        assertEquals(4, layers.size)
        assertFalse(DrawingLayers.canAdd(layers))
        assertNull(DrawingLayers.add(layers))
        assertEquals(4, layers.map { it.id }.toSet().size)
    }

    @Test fun `new layers get the next free default name`() {
        var layers = DrawingLayers.initial()
        layers = DrawingLayers.add(layers)!!
        assertEquals(listOf("Layer 1", "Layer 2"), layers.map { it.name })
        // Rename "Layer 2" away, delete Layer 1, then add: the default must not repeat an existing name.
        layers = DrawingLayers.rename(layers, layers[1].id, "Layer 3")
        layers = DrawingLayers.add(layers)!!
        assertEquals("Layer 3", layers[1].name)
        assertTrue(layers.map { it.name }.toSet().size == 3)
    }

    @Test fun `names are tidied, and blank ones keep the old name`() {
        val layers = DrawingLayers.initial()
        val id = layers[0].id
        assertEquals("Schools I like", DrawingLayers.rename(layers, id, "   Schools    I  like  ")[0].name)
        assertEquals("Layer 1", DrawingLayers.rename(layers, id, "    ")[0].name)
        assertEquals(DrawingLayers.MAX_NAME, DrawingLayers.rename(layers, id, "x".repeat(100))[0].name.length)
        assertEquals("Café ☕ 日本", DrawingLayers.rename(layers, id, "Café ☕ 日本")[0].name)
    }

    @Test fun `renaming one layer leaves the others alone`() {
        val layers = DrawingLayers.add(DrawingLayers.initial())!!
        val renamed = DrawingLayers.rename(layers, layers[1].id, "Commute")
        assertEquals("Layer 1", renamed[0].name)
        assertEquals("Commute", renamed[1].name)
    }

    @Test fun `deleting a layer removes its highlights and no one else's`() {
        val layers = DrawingLayers.add(DrawingLayers.initial())!!
        val strokes = listOf(stroke("a", layers[0].id), stroke("b", layers[1].id), stroke("c", layers[1].id))
        val (left, kept) = DrawingLayers.remove(layers, strokes, layers[1].id)!!
        assertEquals(listOf(layers[0].id), left.map { it.id })
        assertEquals(listOf("a"), kept.map { it.id })
    }

    @Test fun `the last layer cannot be deleted`() {
        val layers = DrawingLayers.initial()
        assertNull(DrawingLayers.remove(layers, emptyList(), layers[0].id))
        val two = DrawingLayers.add(layers)!!
        assertNotNull(DrawingLayers.remove(two, emptyList(), two[0].id))
        assertNull(DrawingLayers.remove(two, emptyList(), "no-such-layer"))
    }

    @Test fun `loaded data is repaired if it doesn't make sense`() {
        val (l1, s1) = DrawingLayers.normalize(emptyList(), listOf(stroke("a", "gone")))
        assertEquals(DrawingLayers.initial(), l1)
        assertEquals(DrawingLayers.DEFAULT_ID, s1[0].layerId)

        val many = List(7) { DrawingLayer("l$it", "L$it") }
        assertEquals(4, DrawingLayers.normalize(many, emptyList()).first.size)

        val dup = listOf(DrawingLayer("x", "one"), DrawingLayer("x", "two"))
        assertEquals(listOf("one"), DrawingLayers.normalize(dup, emptyList()).first.map { it.name })

        val (two, strokes) = DrawingLayers.normalize(listOf(DrawingLayer("p", "P"), DrawingLayer("q", "Q")), listOf(stroke("a", "q"), stroke("b", "zzz")))
        assertEquals("q", strokes.first { it.id == "a" }.layerId)
        assertEquals("p", strokes.first { it.id == "b" }.layerId)
        assertEquals(2, two.size)
    }

    @Test fun `highlights saved before layers existed belong to the first layer`() {
        val old = StoreJson.stroke(org.json.JSONObject(StoreJson.stroke(stroke("old", "x")).toString()).also { it.remove("layer") })
        assertEquals(DrawingLayers.DEFAULT_ID, old.layerId)
    }
}
