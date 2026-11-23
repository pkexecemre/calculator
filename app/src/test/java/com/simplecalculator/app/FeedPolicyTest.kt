package com.simplecalculator.app

import com.simplecalculator.app.data.source.FeedPolicy
import com.simplecalculator.app.data.source.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPolicyTest {

    private fun video(title: String, vkey: String = "abc"): VideoSummary = VideoSummary(
        sourceId = "ph",
        vkey = vkey,
        title = title,
        thumb = "",
        durationSec = 600,
        views = 100,
        uploaderSlug = null,
        uploaderName = null,
        verifiedUploader = false
    )

    @Test
    fun japaneseScriptTitlesAreHeldBackByDefault() {
        val japanese = video("美女が喘ぐ 無修正 中出し")
        assertTrue(FeedPolicy.isJapaneseCatalog(japanese))
        assertFalse(FeedPolicy.visibleInFeed(japanese, showJapanese = false))
        assertTrue(FeedPolicy.visibleInFeed(japanese, showJapanese = true))
    }

    @Test
    fun romajiCatalogueCodesAreHeldBackToo() {
        val jav = video("SSIS-123 Family Therapy Myra Moans", "ssis-123")
        assertTrue(FeedPolicy.isJapaneseCatalog(jav))
        assertFalse(FeedPolicy.visibleInFeed(jav, showJapanese = false))
        assertTrue(FeedPolicy.visibleInFeed(jav, showJapanese = true))
    }

    @Test
    fun englishTitlesThatNameTheGenreAreHeldBackToo() {
        val labelled = video("Japanese stepmom cleans the house")
        assertTrue(FeedPolicy.isJapaneseCatalog(labelled))
        assertFalse(FeedPolicy.visibleInFeed(labelled, showJapanese = false))
    }

    @Test
    fun ordinaryTitlesStayVisible() {
        val western = video("Milf with Big Ass on Public Transport", "696796f4c6e18")
        assertFalse(FeedPolicy.isJapaneseCatalog(western))
        assertTrue(FeedPolicy.visibleInFeed(western, showJapanese = false))
        assertEquals(0.0, FeedPolicy.rankPenalty(western, false), 0.0)
    }

    @Test
    fun heldBackTitlesOnlyGetAPenaltyWhenEverythingWouldBeFiltered() {
        val jav = video("SSIS-456 subtitle leak")
        assertTrue(FeedPolicy.rankPenalty(jav, false) < 0.0)
        assertEquals(0.0, FeedPolicy.rankPenalty(jav, true), 0.0)
    }

    @Test
    fun hexVkeysDoNotTriggerTheCatalogueRule() {
        // No hyphen in the vkey, so a normal Pornhub id cannot trigger the rule.
        assertFalse(FeedPolicy.isJapaneseCatalog(video("Ordinary clip", "696796f4c6e18")))
    }
}
