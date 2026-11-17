package com.simplecalculator.app

import com.simplecalculator.app.data.source.HentaiFlavor
import com.simplecalculator.app.data.source.SearchRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRelevanceTest {

    @Test
    fun keepsStudioTitleAndDropsUnrelatedSlop() {
        assertTrue(SearchRelevance.matches("Shoujo Ramune Episode 4", "shoujo ramune"))
        assertTrue(SearchRelevance.matches("Shoujo Ramune - EP 7", "shoujo ramune"))
        assertFalse(SearchRelevance.matches("VTuber VR chat compilation", "shoujo ramune"))
        assertFalse(SearchRelevance.matches("Random amateur clip", "shoujo ramune"))
    }

    @Test
    fun stopwordsDoNotCreateFalseMatches() {
        assertTrue(SearchRelevance.matches("anything", "the full movie"))
        assertEquals(listOf("shoujo", "ramune"), SearchRelevance.tokens("Shoujo Ramune full movie"))
    }

    @Test
    fun flavorsSeparateStudioAndUserContent() {
        assertEquals(HentaiFlavor.STUDIO, HentaiFlavor.from("Shoujo Ramune Episode 1"))
        assertEquals(HentaiFlavor.VR, HentaiFlavor.from("VTuber VR Sex"))
        assertEquals(HentaiFlavor.USER, HentaiFlavor.from("Iwara amateur clip"))
    }
}
