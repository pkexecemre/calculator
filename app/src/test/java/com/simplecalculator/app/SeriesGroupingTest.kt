package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.SeriesGrouping
import com.simplecalculator.app.data.source.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesGroupingTest {

    private fun video(
        sourceId: String,
        vkey: String,
        title: String,
        category: Category = Category.HENTAI,
        episodeLabel: String? = null
    ) = VideoSummary(
        sourceId = sourceId,
        vkey = vkey,
        title = title,
        thumb = "https://img.example/$vkey.jpg",
        durationSec = 120,
        views = 10,
        uploaderSlug = null,
        uploaderName = null,
        verifiedUploader = false,
        category = category,
        episodeLabel = episodeLabel
    )

    @Test
    fun sameEpisodeFromDifferentSitesBecomesOne() {
        val items = listOf(
            video("hh", "/watch/so-low", "So low Episode 4"),
            video("hc", "/video/91/so-low/episode4/english", "So low - EP 4 (Subbed)"),
            video("hm", "/episodes/so_low-episode-4/", "So_low Episode 4"),
            video("hh", "/watch/so-low-3", "So low Episode 3")
        )

        val groups = SeriesGrouping.group(items)

        assertEquals(1, groups.size)
        assertEquals("So low", groups[0].title)
        assertEquals(2, groups[0].episodeCount)
        assertEquals(listOf("3", "4"), groups[0].videos.map { SeriesGrouping.episodeKey(it) })
        // Mirrors of the same episode are dropped, so fewer sources remain.
        assertTrue(groups[0].sourceIds.isNotEmpty())
    }

    @Test
    fun unexpandedCardStillAdvertisesItsEpisodeRange() {
        val card = video("akih", "/shoujo-ramune/", "Shoujo Ramune", episodeLabel = "EP 1-7")
        val group = SeriesGrouping.group(listOf(card)).single()
        assertEquals(1, group.episodeCount)
        assertEquals(7, group.advertisedEpisodes)
        assertEquals(7, group.displayEpisodes)
    }

    @Test
    fun subtitleVariantsCollapseToTheSameSeries() {
        val items = listOf(
            video("akih", "/watch/a/", "Shoujo Ramune Vol 1 Sub-Eng"),
            video("akih", "/watch/b/", "Shoujo Ramune Vol 1 ซับไทย"),
            video("hh", "/watch/c/", "Shoujo Ramune (RemoveCensored) Vol 1")
        )
        val groups = SeriesGrouping.group(items)
        assertEquals(1, groups.size)
        assertEquals("Shoujo Ramune", groups[0].title)
        assertEquals(1, groups[0].episodeCount)
    }

    @Test
    fun differentSeriesStaySeparateAndLiveIsIgnored() {
        val items = listOf(
            video("hh", "/watch/a", "A series Episode 1"),
            video("hh", "/watch/b", "B series Episode 1"),
            video("ph", "abc", "A series Episode 1", Category.LIVE)
        )

        val groups = SeriesGrouping.group(items)
        assertEquals(2, groups.size)
        assertEquals(setOf("a series", "b series"), groups.map { it.key }.toSet())
        assertTrue(groups.all { it.episodeCount == 1 })
    }
}
