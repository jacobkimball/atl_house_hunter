package com.househunter.app

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility

/** The things the Layers panel can show or hide (requirement MAP-7). */
enum class LayerGroup(val label: String, val detail: String) {
    Neighborhoods("Neighborhoods", "Official boundaries, plus finer outlines when zoomed in"),
    Traffic("High-traffic roads", "Interstates and main roads. Lighter gray lines are other arterials"),
    Rail("Railroads & MARTA", "Freight lines, MARTA rail and stations"),
    Schools("Elementary school zones", "Atlanta, DeKalb, Fulton, Decatur, Cobb and Marietta zones. Not covered: Clayton, Gwinnett"),
    Markers("My markers", "Your pins and notes"),
}

object MapLayers {
    // Layer ids from style.json, HighlightLayer and MarkerLayer.
    private val layerIds = mapOf(
        LayerGroup.Neighborhoods to listOf(
            "neighborhood-fill", "civic-fill", "subdivision-fill", "neighborhood-outline", "civic-outline",
            "subdivision-outline", "label-neighborhood", "label-civic", "label-osm-neighborhood", "label-subdivision",
        ),
        LayerGroup.Rail to listOf(
            "rail-freight-minor", "rail-marta-minor", "rail-marta-tunnel", "rail-freight", "rail-freight-ties",
            "rail-marta-casing", "rail-marta", "stations-circle", "label-station", "label-rail",
        ),
        LayerGroup.Markers to listOf("markers-pin", "markers-label"),
        LayerGroup.Schools to listOf(
            "school-zone-fill", "school-zone-outline", "label-school-zone",
            "school-zone-upper-fill", "school-zone-upper-outline", "label-school-zone-upper",
        ),
    )

    fun apply(style: Style, hidden: Set<LayerGroup>) {
        for ((group, ids) in layerIds) {
            val value = if (group in hidden) Property.NONE else Property.VISIBLE
            ids.forEach { style.getLayer(it)?.setProperties(visibility(value)) }
        }
        setTrafficEmphasis(style, LayerGroup.Traffic !in hidden)
    }

    /**
     * "Hiding" the high-traffic roads can't remove them (they're streets); instead they drop back to looking like
     * any ordinary road, so the map is calmer without gaps.
     */
    private fun setTrafficEmphasis(style: Style, emphasized: Boolean) {
        fun widths(vararg stops: Pair<Int, Float>) =
            lineWidth(interpolate(exponential(1.6f), zoom(), *stops.map { stop(it.first, it.second) }.toTypedArray()))
        style.getLayer("high-traffic-casing")?.setProperties(
            if (emphasized) lineColor("#ffffff") else lineColor("#c9c3b5"),
            if (emphasized) widths(9 to 2f, 12 to 5f, 15 to 10f, 18 to 34f) else widths(10 to 0.6f, 15 to 4.6f, 18 to 22f),
        )
        style.getLayer("high-traffic")?.setProperties(
            if (emphasized) lineColor("#2b2f36") else lineColor("#fffdf5"),
            if (emphasized) widths(9 to 1.2f, 12 to 3.2f, 15 to 7f, 18 to 27f) else widths(10 to 0.4f, 15 to 3.4f, 18 to 19f),
        )
        style.getLayer("label-high-traffic")?.setProperties(
            if (emphasized) textColor("#ffffff") else textColor("#454a54"),
            if (emphasized) textHaloColor("#2b2f36") else textHaloColor("rgba(255,255,255,0.9)"),
            if (emphasized) textHaloWidth(1.0f) else textHaloWidth(1.6f),
        )
    }
}
