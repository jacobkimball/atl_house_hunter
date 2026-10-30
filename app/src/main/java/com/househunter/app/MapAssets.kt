package com.househunter.app

import android.content.Context
import java.io.File

/**
 * MapLibre reads tile and GeoJSON sources from real files, not from inside the APK,
 * so the bundled data files are copied into app storage on first launch (and again if they change size).
 */
object MapAssets {
    private const val TILES = "atlanta.pmtiles"

    /** style.json placeholder -> bundled GeoJSON file it points at. */
    private val GEOJSON = mapOf(
        "{{NEIGHBORHOODS_URL}}" to "atlanta_neighborhoods.geojson",
        "{{SUBDIVISIONS_URL}}" to "subdivisions.geojson",
        "{{OSM_NEIGHBORHOODS_URL}}" to "osm_neighborhoods.geojson",
        "{{RAIL_URL}}" to "rail.geojson",
        "{{STATIONS_URL}}" to "stations.geojson",
        "{{SCHOOL_ZONES_URL}}" to "school_zones.geojson",
    )

    /** Builds the style JSON with the on-device file locations filled in. */
    fun styleJson(context: Context): String {
        var style = context.assets.open("style.json").bufferedReader().use { it.readText() }
            .replace("{{TILES_URL}}", "pmtiles://file://${copyToFiles(context, TILES).absolutePath}")
        for ((placeholder, name) in GEOJSON) {
            style = style.replace(placeholder, "file://${copyToFiles(context, name).absolutePath}")
        }
        return style
    }

    private fun copyToFiles(context: Context, name: String): File {
        val out = File(context.filesDir, name)
        val size = context.assets.openFd(name).use { it.length }
        if (!out.exists() || out.length() != size) {
            context.assets.open(name).use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        return out
    }
}
