package com.househunter.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SearchIndexTest {
    private fun e(name: String, type: Int, area: String = "", length: Int = 0) = SearchEntry(name, type, 33.78, -84.38, -84.39, 33.77, -84.37, 33.79, area, length)

    private val index = SearchIndex(listOf(
        e("Peachtree Street Northeast", SearchType.STREET, "Downtown"),
        e("Peachtree Street Northeast", SearchType.STREET, "Brookwood"),
        e("Peachtree Hills", SearchType.NEIGHBORHOOD, "Atlanta"),
        e("Peachtree Heights East", SearchType.NEIGHBORHOOD, "Atlanta"),
        e("Peachtree Center (MARTA)", SearchType.STATION),
        e("North Highland Avenue Northeast", SearchType.STREET, "Virginia-Highland"),
        e("Highland Park", SearchType.NEIGHBORHOOD, "Atlanta"),
        e("Edgewood/Candler Park", SearchType.NEIGHBORHOOD, "Atlanta"),
        e("Café Circle", SearchType.STREET, "Decatur"),
        e("Ponce de Leon Avenue Northeast", SearchType.STREET, "Poncey-Highland"),
        e("Decatur", SearchType.CITY),
        e("Decatur Street Southeast", SearchType.STREET, "Downtown"),
        e("Old Fourth Ward", SearchType.NEIGHBORHOOD, "Atlanta"),
        e("10th Street Northeast", SearchType.STREET, "Midtown"),
    ))

    private fun names(q: String, limit: Int = 12) = index.search(q, limit).map { it.name }

    @Test fun `typing part of a name finds it`() {
        assertTrue("Ponce de Leon Avenue Northeast" in names("ponce"))
        assertTrue("Old Fourth Ward" in names("fourth"))
        assertTrue("Old Fourth Ward" in names("old fou"))
    }

    @Test fun `abbreviations work in either direction`() {
        assertEquals(listOf("Peachtree Street Northeast", "Peachtree Street Northeast"), names("peachtree st ne"))
        assertEquals("North Highland Avenue Northeast", names("n highland ave ne").first())
        assertEquals("Ponce de Leon Avenue Northeast", names("ponce de leon ave").first())
    }

    @Test fun `word order and case and punctuation do not matter`() {
        assertTrue("North Highland Avenue Northeast" in names("HIGHLAND north"))
        assertEquals("Edgewood/Candler Park", names("edgewood candler").first())
        assertEquals("Edgewood/Candler Park", names("Edgewood / Candler").first())
    }

    @Test fun `accents are ignored`() {
        assertEquals("Café Circle", names("cafe circle").first())
        assertEquals("Café Circle", names("café").first())
    }

    @Test fun `numbers in street names work`() {
        assertEquals("10th Street Northeast", names("10th st").first())
        assertEquals("10th Street Northeast", names("10").first())
    }

    @Test fun `better matches come first and neighborhoods beat streets when otherwise equal`() {
        // "Decatur" the city is an exact match; the street only starts with it.
        assertEquals(listOf("Decatur", "Decatur Street Southeast"), names("decatur").take(2).filter { it in setOf("Decatur", "Decatur Street Southeast") })
        val peach = names("peachtree")
        assertTrue(peach.indexOf("Peachtree Hills") < peach.indexOf("Peachtree Street Northeast"))
    }

    @Test fun `among equal matches the longer, more important street comes first`() {
        val ranked = SearchIndex(listOf(
            e("Ponce de Leon Court", SearchType.STREET, "Winnona Park", length = 120),
            e("Ponce de Leon Place", SearchType.STREET, "Decatur", length = 90),
            e("Ponce de Leon Avenue Northeast", SearchType.STREET, "Druid Hills", length = 4300),
        ))
        assertEquals("Ponce de Leon Avenue Northeast", ranked.search("ponce de leon").first().name)
    }

    @Test fun `results say where they are`() {
        val hits = index.search("peachtree street")
        assertEquals(setOf("Street · Downtown", "Street · Brookwood"), hits.filter { it.name == "Peachtree Street Northeast" }.map { it.subtitle }.toSet())
        assertEquals("MARTA station", index.search("peachtree center").first().subtitle)
    }

    @Test fun `blank and nonsense queries find nothing`() {
        assertTrue(index.search("").isEmpty())
        assertTrue(index.search("   ").isEmpty())
        assertTrue(index.search("///").isEmpty())
        assertTrue(index.search("zzzzqqq").isEmpty())
    }

    @Test fun `a street name repeated many times is trimmed and the limit is respected`() {
        val many = SearchIndex(List(10) { e("Oak Drive", SearchType.STREET, "Area $it") } + List(5) { e("Oak Hollow $it", SearchType.SUBDIVISION) })
        assertEquals(3, many.search("oak drive").count { it.name == "Oak Drive" })
        assertEquals(4, many.search("oak", limit = 4).size)
    }

    @Test fun `searching my notes finds words anywhere in the text`() {
        val a = NoteMarker(id = "a", lat = 1.0, lon = 1.0, note = "Quiet street. Great coffee shop nearby", updatedAt = 10)
        val b = NoteMarker(id = "b", lat = 1.0, lon = 1.0, note = "Loud at night", updatedAt = 20)
        val c = NoteMarker(id = "c", lat = 1.0, lon = 1.0, note = "COFFEE roaster, quiet", updatedAt = 30)
        assertEquals(listOf("c", "a"), SearchIndex.searchNotes(listOf(a, b, c), "coffee").map { it.id })
        assertEquals(listOf("c", "a"), SearchIndex.searchNotes(listOf(a, b, c), "quiet coffee").map { it.id })
        assertTrue(SearchIndex.searchNotes(listOf(a, b, c), "").isEmpty())
        assertTrue(SearchIndex.searchNotes(listOf(a, b, c), "pizza").isEmpty())
    }

    @Test fun `the real Atlanta index finds real places`() {
        val file = File("../data/assets/search_index.json")
        assumeTrue("search_index.json not built", file.exists())
        val real = SearchIndex.parse(file.readText())
        fun top(q: String) = real.search(q).firstOrNull()
        assertTrue(real.entries.size > 20_000)
        assertEquals("Five Points (MARTA)", top("five points")?.name)
        assertEquals(SearchType.STATION, top("five points")?.type)
        assertTrue("Ponce de Leon Avenue", real.search("ponce de leon").any { it.name.startsWith("Ponce de Leon Avenue") })
        assertTrue(real.search("old fourth ward").any { it.name == "Old Fourth Ward" && it.type == SearchType.NEIGHBORHOOD })
        assertTrue(real.search("sandy springs").any { it.type == SearchType.CITY })
        assertTrue(real.search("n highland ave").any { it.name.startsWith("North Highland Avenue") })
        assertTrue("streets exist for a small residential name", real.search("kayron drive").isNotEmpty())
        val ponce = real.search("ponce de leon")
        assertTrue("the main avenue should rank first, got ${ponce.map { it.name }}", ponce.first().name == "Ponce de Leon Avenue Northeast")
        println("real index: ${real.entries.size} entries; 'peachtree st': ${real.search("peachtree st").take(4).map { it.name + " [" + it.subtitle + "]" }}")
    }
}
