package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.ContentFilters
import com.simplecalculator.app.data.source.JavTitles
import com.simplecalculator.app.data.source.VideoSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFiltersTest {

    private fun video(source: String, key: String, title: String) = VideoSummary(
        sourceId = source,
        vkey = key,
        title = title,
        thumb = "",
        durationSec = 600,
        views = 100,
        uploaderSlug = null,
        uploaderName = null,
        verifiedUploader = false
    )

    @Test
    fun studioReleaseOutranksToiletAndHiddenCam() {
        val studio = ContentFilters.qualityScore("SSIS-123 新人NO.1STYLE", "ssis-123", "msav")
        val toilet = ContentFilters.qualityScore("トイレ盗撮 隠し撮り", "toilet-1", "tmo")
        val hiddenCam = ContentFilters.qualityScore("Hidden camera in the toilet", "hidden-cam", "tmo")
        assertTrue(studio > toilet + 2.0)
        assertTrue(studio > hiddenCam + 2.0)
        assertTrue(ContentFilters.isLowQuality("トイレ盗撮 隠し撮り", "toilet-1"))
        assertTrue(ContentFilters.isLowQuality("Hidden camera in the toilet", "hidden-cam"))
    }

    @Test
    fun fc2AmateurIsPenalized() {
        val studio = ContentFilters.qualityScore("SSIS-123 新人", "ssis-123", "msav")
        val fc2 = ContentFilters.qualityScore("FC2-PPV-5001234", "fc2-ppv-5001234", "msav")
        assertTrue(ContentFilters.isAmateur("FC2-PPV-5001234", "fc2-ppv-5001234"))
        assertTrue(studio > fc2 + 1.5)

        // Same source and no catalogue code: only the amateur marker differs.
        val plain = ContentFilters.qualityScore("Couple at home", "plain-1", "msav")
        val amateur = ContentFilters.qualityScore("Amateur homemade clip", "amateur-1", "msav")
        assertTrue(plain > amateur)
        assertTrue(ContentFilters.isAmateur("素人 個人撮影", "amateur-2"))
    }

    @Test
    fun releaseIdentitySeparatesSubtitledAndUncensoredCuts() {
        val normal = ContentFilters.releaseIdentity("SSIS-960 タイトル", "ssis-960")
        assertEquals("SSIS-960", normal)
        assertEquals(normal, ContentFilters.releaseIdentity("SSIS-960 タイトル", "ssis-960"))

        val subtitled = ContentFilters.releaseIdentity(
            "SSIS-960 タイトル english subtitle", "ssis-960-sub"
        )
        val uncensored = ContentFilters.releaseIdentity(
            "SSIS-960 uncensored leak", "ssis-960-uncen"
        )
        val both = ContentFilters.releaseIdentity(
            "SSIS-960 english subtitle uncensor", "ssis-960-sub-unc"
        )
        assertEquals("SSIS-960:sub", subtitled)
        assertEquals("SSIS-960:unc", uncensored)
        assertEquals("SSIS-960:sub:unc", both)
        assertNotEquals(normal, subtitled)
        assertNotEquals(subtitled, uncensored)
        assertNull(ContentFilters.releaseIdentity("no code here", "free-1"))
    }

    @Test
    fun javTitlesHeuristicsWorkOnJapaneseAndRomaji() {
        assertTrue(JavTitles.isUncensored("無修正 中出し", "ssis-960"))
        assertTrue(JavTitles.isSubtitled("SSIS-960 英文字幕", "ssis-960"))
        assertTrue(JavTitles.isSubtitled("SSIS-960 english sub", "ssis-960"))
        assertTrue(JavTitles.isUncensored("SSIS-960", "ssis-960-uncensor-leak"))
        assertTrue(JavTitles.isSubtitled("SSIS-960", "ssis-960-eng-sub"))

        assertFalse(JavTitles.isUncensored("SSIS-960 通常版", "ssis-960"))
        assertTrue(JavTitles.isCensored("SSIS-960 通常版", "ssis-960"))
        assertFalse(JavTitles.isSubtitled("SSIS-960 通常版", "ssis-960"))
    }

    @Test
    fun lowQualityTitlesAreDetected() {
        assertTrue(ContentFilters.isLowQuality("トイレ盗撮 隠し撮り", "toilet"))
        assertTrue(ContentFilters.isLowQuality("Hidden Camera in the toilet", "hidden-cam"))
        assertTrue(ContentFilters.isLowQuality("Voyeur peeping compilation", "voyeur"))
        assertTrue(ContentFilters.isLowQuality("【50人】collection zip", "KISSJAV_6iiHLnt3"))
        assertTrue(ContentFilters.isLowQuality("総集編", "collection-1"))

        assertFalse(ContentFilters.isLowQuality("SSIS-123 新人NO.1STYLE", "ssis-123"))
        assertFalse(ContentFilters.isLowQuality("Office lady drama", "office-1"))
        assertFalse(ContentFilters.isLowQuality("A scatterbrained romance", "romance-1"))
        assertFalse(ContentFilters.isLowQuality("Peeling fruit", "fruit-1"))
    }

    @Test
    fun rankBonusIsJavOnly() {
        val studio = video("msav", "ssis-123", "SSIS-123 新人")
        assertEquals(
            ContentFilters.qualityScore(studio.title, studio.vkey, studio.sourceId),
            ContentFilters.rankBonus(Category.JAV)(studio),
            0.0
        )
        assertEquals(0.0, ContentFilters.rankBonus(Category.LIVE)(studio), 0.0)
        assertEquals(0.0, ContentFilters.rankBonus(Category.HENTAI)(studio), 0.0)
        assertEquals(0.0, ContentFilters.rankBonus(Category.ARCHIVE)(studio), 0.0)
    }

    @Test
    fun productCodeIsUppercasedAndFallsBackToVkey() {
        assertEquals("SSIS-123", ContentFilters.productCode("SSIS-123 新人", "ignored"))
        assertEquals("SSIS-123", ContentFilters.productCode("新人", "ssis-123"))
        assertEquals("SSIS-123", ContentFilters.productCode("新人", "ssis_123"))
        assertNull(ContentFilters.productCode("no code here", "free-1"))
    }
}
