package com.househunter.app

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.exponential
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * The pen's highlights. Saved strokes and the stroke being drawn share one look (semi-transparent, round-capped,
 * a width that grows with zoom so it stays readable). Both sit under the street-name labels so names stay legible.
 */
object HighlightLayer {
    private const val SAVED_SOURCE = "highlights"
    private const val SAVED_LAYER = "highlights"
    private const val DRAFT_SOURCE = "highlight-draft"
    private const val DRAFT_LAYER = "highlight-draft"
    private const val BELOW_LAYER = "label-minor" // first label layer in style.json

    fun install(style: Style) {
        style.addSource(GeoJsonSource(SAVED_SOURCE))
        style.addSource(GeoJsonSource(DRAFT_SOURCE))
        style.addLayerBelow(highlightLayer(SAVED_LAYER, SAVED_SOURCE), BELOW_LAYER)
        style.addLayerBelow(highlightLayer(DRAFT_LAYER, DRAFT_SOURCE), BELOW_LAYER)
    }

    fun update(style: Style, strokes: List<Stroke>) {
        val features = strokes.map { s ->
            val lines = s.parts.map { part -> part.map { Point.fromLngLat(it.lon, it.lat) } }
            Feature.fromGeometry(MultiLineString.fromLngLats(lines)).also {
                it.addStringProperty("color", PenColors.hex[s.color])
            }
        }
        style.getSourceAs<GeoJsonSource>(SAVED_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun updateDraft(style: Style, parts: List<List<LatLng>>, color: Int) {
        val lines = parts.filter { it.size >= 2 }.map { part -> part.map { Point.fromLngLat(it.longitude, it.latitude) } }
        val collection = if (lines.isEmpty()) {
            FeatureCollection.fromFeatures(emptyList<Feature>())
        } else {
            FeatureCollection.fromFeature(
                Feature.fromGeometry(MultiLineString.fromLngLats(lines)).also { it.addStringProperty("color", PenColors.hex[color]) }
            )
        }
        style.getSourceAs<GeoJsonSource>(DRAFT_SOURCE)?.setGeoJson(collection)
    }

    private fun highlightLayer(id: String, source: String) = LineLayer(id, source).withProperties(
        lineColor(Expression.toColor(Expression.get("color"))),
        lineWidth(
            interpolate(exponential(1.6f), zoom(), stop(10, 3f), stop(12, 5f), stop(15, 10f), stop(18, 30f))
        ),
        lineOpacity(0.62f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )
}
