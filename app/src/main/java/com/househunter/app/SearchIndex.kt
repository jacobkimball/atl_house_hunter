package com.househunter.app

import org.json.JSONArray
import java.text.Normalizer

/** What kind of thing a search result is (matches the numbers in data/build_search_index.py). */
object SearchType {
    const val CITY = 0
    const val NEIGHBORHOOD = 1
    const val SUBDIVISION = 2
    const val STATION = 3
    const val STREET = 4

    fun label(type: Int) = when (type) {
        CITY -> "City"
        NEIGHBORHOOD -> "Neighborhood"
        SUBDIVISION -> "Subdivision"
        STATION -> "MARTA station"
        else -> "Street"
    }
}

class SearchEntry(
    val name: String,
    val type: Int,
    val lat: Double,
    val lon: Double,
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
    /** A short "where is it" label, e.g. "Ansley Park", so two streets with one name can be told apart. */
    val area: String,
    /** Street length in meters (0 for anything else): among equal matches, longer streets are the more important ones. */
    val length: Int = 0,
) {
    internal val tokens: List<String> = SearchText.tokens(name)
    val subtitle: String get() = if (area.isBlank() || type == SearchType.STATION) SearchType.label(type) else "${SearchType.label(type)} · $area"
}

object SearchText {
    private val EXPANSIONS = mapOf(
        "st" to "street", "ave" to "avenue", "av" to "avenue", "rd" to "road", "dr" to "drive", "blvd" to "boulevard",
        "ln" to "lane", "ct" to "court", "cir" to "circle", "pl" to "place", "pkwy" to "parkway", "hwy" to "highway",
        "trl" to "trail", "ter" to "terrace", "sq" to "square", "mt" to "mount", "ft" to "fort",
        "n" to "north", "s" to "south", "e" to "east", "w" to "west",
        "ne" to "northeast", "nw" to "northwest", "se" to "southeast", "sw" to "southwest",
    )

    /** Lowercase words with accents and punctuation removed: "Edgewood/Candler Park" -> [edgewood, candler, park]. */
    fun tokens(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFD).lowercase()
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim().split(' ').filter { it.isNotEmpty() }

    /** Does a typed word (maybe half-typed, maybe an abbreviation) fit this word of a name? */
    fun fits(typed: String, word: String): Boolean {
        if (word.startsWith(typed)) return true
        val full = EXPANSIONS[typed] ?: return false
        return word.startsWith(full)
    }
}

/** Offline search over neighborhoods, streets, stations and towns. */
class SearchIndex(val entries: List<SearchEntry>) {
    /**
     * Best matches first. Every typed word must fit some word of the name (order doesn't matter), but names that
     * start with what you typed, or equal it, rank higher; among equals, neighborhoods and towns come before streets,
     * and longer streets before short ones.
     */
    fun search(query: String, limit: Int = 12): List<SearchEntry> {
        val typed = SearchText.tokens(query)
        if (typed.isEmpty()) return emptyList()
        class Hit(val entry: SearchEntry, val quality: Int)
        val hits = ArrayList<Hit>()
        for (e in entries) {
            val words = e.tokens
            if (!typed.all { t -> words.any { SearchText.fits(t, it) } }) continue
            val startsWith = typed.indices.all { i -> i < words.size && SearchText.fits(typed[i], words[i]) }
            val quality = when {
                startsWith && typed.size == words.size -> 0
                startsWith -> 1
                else -> 2
            }
            hits.add(Hit(e, quality))
        }
        val sorted = hits.sortedWith(compareBy({ it.quality }, { typeRank(it.entry.type) }, { -it.entry.length }, { it.entry.name.length }, { it.entry.name }))
        // A few stretches of one street name are useful ("Oak Drive" in three places); a dozen would crowd out the rest.
        val perName = HashMap<String, Int>()
        val out = ArrayList<SearchEntry>()
        for (h in sorted) {
            val n = perName.merge(h.entry.name, 1, Int::plus)!!
            if (n <= 3) out.add(h.entry)
            if (out.size == limit) break
        }
        return out
    }

    private fun typeRank(type: Int) = when (type) {
        SearchType.CITY, SearchType.NEIGHBORHOOD -> 0
        SearchType.STATION -> 1
        SearchType.STREET -> 2
        else -> 3
    }

    companion object {
        /** Reads data/assets/search_index.json: an array of [name, type, lat, lon, west, south, east, north, area, length]. */
        fun parse(json: String): SearchIndex {
            val array = JSONArray(json)
            return SearchIndex(List(array.length()) { i ->
                val r = array.getJSONArray(i)
                SearchEntry(r.getString(0), r.getInt(1), r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7), r.optString(8, ""), r.optInt(9, 0))
            })
        }

        /** My own notes that contain every typed word, most recently edited first. */
        fun searchNotes(markers: List<NoteMarker>, query: String, limit: Int = 4): List<NoteMarker> {
            val words = SearchText.tokens(query)
            if (words.isEmpty()) return emptyList()
            return markers
                .filter { m -> val text = SearchText.tokens(m.note).joinToString(" "); words.all { it in text } }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }
    }
}
