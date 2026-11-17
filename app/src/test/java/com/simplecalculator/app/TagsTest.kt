package com.simplecalculator.app

import com.simplecalculator.app.data.source.Tags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {

    @Test
    fun benignWordsAreNotBlockedBySubstring() {
        assertTrue(Tags.isAllowed(emptyList(), "Childhood Friends Forever"))
        assertTrue(Tags.isAllowed(emptyList(), "Kidnapped by a Vampire"))
        assertTrue(Tags.isAllowed(emptyList(), "Minority Report Parody"))
        assertTrue(Tags.isAllowed(listOf("childhood", "romance"), "Childhood Friend"))
        assertFalse(Tags.isBlocked("childhood"))
        assertFalse(Tags.isBlocked("minority"))
        assertFalse(Tags.isBlocked("kidnap"))
    }

    @Test
    fun explicitTagsAreStillBlocked() {
        assertFalse(Tags.isAllowed(listOf("loli"), "Innocent title"))
        assertFalse(Tags.isAllowed(listOf("shotacon"), "Innocent title"))
        assertFalse(Tags.isAllowed(listOf("underage"), "Innocent title"))
        assertFalse(Tags.isAllowed(listOf("pre-teen"), "Innocent title"))
    }

    @Test
    fun onlyHighConfidenceTitlePhrasesAreBlocked() {
        assertFalse(Tags.isAllowed(emptyList(), "Lolicon Compilation"))
        assertFalse(Tags.isAllowed(emptyList(), "Shotacon Special"))
        assertFalse(Tags.isAllowed(emptyList(), "pre teen adventure"))
        assertFalse(Tags.isAllowed(emptyList(), "Under Age Scenario"))
        assertFalse(Tags.isAllowed(emptyList(), "子供の小学生"))
        // Bare generic words stay allowed to avoid killing normal hentai titles.
        assertTrue(Tags.isAllowed(emptyList(), "Childhood Teacher"))
        assertTrue(Tags.isAllowed(emptyList(), "Lolita Fashion Review"))
    }
}
