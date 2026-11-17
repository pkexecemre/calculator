package com.simplecalculator.app

import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.source.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RankerTest {

    private fun video(n: Int, uploader: String?): VideoSummary = VideoSummary(
        sourceId = "ph",
        vkey = n.toString(),
        title = "Video $n",
        thumb = "",
        durationSec = 100,
        views = 10,
        uploaderSlug = uploader,
        uploaderName = uploader,
        verifiedUploader = false
    )

    @Test
    fun mixAlternatesTwoGeneralItemsWithOneStarred() {
        val general = listOf(video(3, null), video(4, null), video(5, null), video(6, null))
        val starred = listOf(video(1, "alice"), video(2, "alice"))

        val mixed = Ranker().mix(general, starred)

        assertEquals(listOf("ph:3", "ph:4", "ph:1", "ph:5", "ph:6", "ph:2"), mixed.map { it.key })
    }

    @Test
    fun mixWithNoStarredItemsReturnsGeneralUnchanged() {
        val general = listOf(video(1, null), video(2, null))
        assertEquals(general, Ranker().mix(general, emptyList()))
    }
}
