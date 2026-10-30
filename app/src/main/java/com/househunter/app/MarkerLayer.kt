package com.househunter.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Draws saved markers on the map: a pin at the spot, plus the first line of the note when zoomed in. */
object MarkerLayer {
    const val PIN_COLOR = 0xFFD6336C.toInt()

    private const val SOURCE = "markers"
    private const val PIN_LAYER = "markers-pin"
    private const val LABEL_LAYER = "markers-label"
    private const val IMAGE = "marker-pin"

    fun install(style: Style, density: Float) {
        style.addImage(IMAGE, pinBitmap(density))
        style.addSource(GeoJsonSource(SOURCE))
        style.addLayer(
            SymbolLayer(PIN_LAYER, SOURCE).withProperties(
                iconImage(IMAGE),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            )
        )
        style.addLayer(
            SymbolLayer(LABEL_LAYER, SOURCE).apply {
                minZoom = 14.5f
                setFilter(Expression.neq(Expression.get("preview"), ""))
            }.withProperties(
                textField(Expression.get("preview")),
                textFont(arrayOf("Noto Sans Medium")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.3f)),
                textMaxWidth(9f),
                textColor(0xFF7A1237.toInt()),
                textHaloColor(0xFFFFFFFF.toInt()),
                textHaloWidth(1.8f),
            )
        )
    }

    fun update(style: Style, markers: List<NoteMarker>) {
        val features = markers.map { m ->
            Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).also {
                it.addStringProperty("id", m.id)
                it.addStringProperty("preview", notePreview(m.note))
            }
        }
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** The marker under a tap, if any. The radius makes the pin easy to hit with a fingertip. */
    fun idAt(map: MapLibreMap, tap: PointF, radiusPx: Float): String? {
        val box = RectF(tap.x - radiusPx, tap.y - radiusPx, tap.x + radiusPx, tap.y + radiusPx)
        return map.queryRenderedFeatures(box, PIN_LAYER).firstOrNull()?.getStringProperty("id")
    }

    /** A teardrop pin with its tip at the bottom centre, so the tip marks the exact spot. */
    private fun pinBitmap(density: Float): Bitmap {
        val w = (36 * density).toInt()
        val h = (48 * density).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cx = w / 2f
        val r = 15 * density
        val cy = r + 2 * density
        val tip = h - 1.5f * density
        val head = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }
        val tail = Path().apply {
            moveTo(cx - r * 0.86f, cy + r * 0.5f)
            lineTo(cx, tip)
            lineTo(cx + r * 0.86f, cy + r * 0.5f)
            close()
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeJoin = Paint.Join.ROUND
            color = 0xFFFFFFFF.toInt()
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_COLOR }
        listOf(head, tail).forEach { canvas.drawPath(it, outline) }
        listOf(head, tail).forEach { canvas.drawPath(it, fill) }
        canvas.drawCircle(cx, cy, r * 0.4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        return bitmap
    }

    private const val RESULT_SOURCE = "search-result"

    /** A temporary ring marking what a search found, so you can see it at any zoom. Cleared after a few seconds. */
    fun installSearchResult(style: Style) {
        style.addSource(GeoJsonSource(RESULT_SOURCE))
        style.addLayer(
            CircleLayer("search-result-halo", RESULT_SOURCE).withProperties(
                circleRadius(20f), circleColor(0x332F8F9D), circleStrokeColor(0xFF2F8F9D.toInt()), circleStrokeWidth(3f),
            )
        )
        style.addLayer(
            CircleLayer("search-result-dot", RESULT_SOURCE).withProperties(
                circleRadius(5f), circleColor(0xFF2F8F9D.toInt()), circleStrokeColor(0xFFFFFFFF.toInt()), circleStrokeWidth(2f),
            )
        )
    }

    fun showSearchResult(style: Style, lat: Double, lon: Double) {
        style.getSourceAs<GeoJsonSource>(RESULT_SOURCE)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)))
    }

    fun clearSearchResult(style: Style) {
        style.getSourceAs<GeoJsonSource>(RESULT_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
    }
}
