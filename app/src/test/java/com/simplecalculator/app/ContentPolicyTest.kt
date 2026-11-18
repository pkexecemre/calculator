package com.simplecalculator.app

import com.simplecalculator.app.data.net.NetworkErrors
import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.ContentFilters
import com.simplecalculator.app.data.source.VideoSummary
import com.simplecalculator.app.data.stt.ModelCatalog
import com.simplecalculator.app.data.source.generic.ExtraSites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class ContentPolicyTest {

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
    fun hiddenCameraAndToiletTitlesAreRejected() {
        assertTrue(ContentFilters.isLowQuality("トイレ盗撮 隠し撮り", "toilet"))
        assertTrue(ContentFilters.isLowQuality("Hidden Camera in the toilet", "hidden-cam"))
        assertTrue(ContentFilters.isLowQuality("Voyeur peeping compilation", "voyeur"))
        assertTrue(ContentFilters.isLowQuality("toire nozoki", "toire-1"))
        assertFalse(ContentFilters.isLowQuality("SSIS-123 新人NO.1STYLE", "ssis-123"))
        assertFalse(ContentFilters.isLowQuality("Office lady drama", "office-1"))
        assertFalse(ContentFilters.isLowQuality("A scatterbrained romance", "romance-1"))
        assertFalse(ContentFilters.isLowQuality("Peeling fruit", "fruit-1"))
    }

    @Test
    fun liveCjkTitlesAreDetected() {
        assertTrue(ContentFilters.isCjkTitle("【素人】お風呂"))
        assertTrue(ContentFilters.isCjkTitle("偷拍"))
        assertFalse(ContentFilters.isCjkTitle("Amateur couple at home"))
    }

    @Test
    fun liveFeedDropsJapaneseSourcesAndCjkTitles() {
        val western = video("xv", "west", "Amateur couple at home")
        val japaneseSource = video("avg", "jp-source", "Japanese amateur")
        val cjkTitle = video("xv", "jp-title", "【素人】お風呂")
        val filtered = ContentFilters.filterFeed(
            listOf(western, japaneseSource, cjkTitle),
            Category.LIVE,
            sourceFilter = null
        )
        assertEquals(listOf("west"), filtered.map { it.vkey })
    }

    @Test
    fun explicitlySelectedJapaneseLiveSourceStillWorks() {
        val japaneseSource = video("avg", "jp-source", "Japanese amateur")
        val cjkTitle = video("avg", "jp-title", "【素人】お風呂")
        val filtered = ContentFilters.filterFeed(
            listOf(japaneseSource, cjkTitle),
            Category.LIVE,
            sourceFilter = "avg"
        )
        assertEquals(2, filtered.size)
    }

    @Test
    fun javFeedDropsVoyeurTitlesButKeepsStudioReleases() {
        val studio = video("msav", "ssis-123", "SSIS-123 新人")
        val toilet = video("msav", "toilet-1", "トイレ盗撮")
        val filtered = ContentFilters.filterFeed(
            listOf(studio, toilet),
            Category.JAV,
            sourceFilter = null
        )
        assertEquals(listOf("ssis-123"), filtered.map { it.vkey })
    }

    @Test
    fun professionalReleasesOutrankAmateurFeeds() {
        val studio = ContentFilters.qualityScore("SSIS-123 新人", "ssis-123", "msav")
        val amateur = ContentFilters.qualityScore("素人 個人撮影", "amateur-1", "tmo")
        assertTrue(studio > amateur + 2.0)
        val fc2 = ContentFilters.qualityScore("FC2-PPV-5001234", "fc2-ppv-5001234", "msav")
        assertTrue(studio > fc2 + 1.5)
    }

    @Test
    fun japaneseLiveAndAmateurSourcesAreOptIn() {
        assertFalse(ExtraSites.all.first { it.id == "141" }.defaultInFeed)
        assertFalse(ExtraSites.all.first { it.id == "tmo" }.defaultInFeed)
        assertTrue(ExtraSites.all.first { it.id == "kjv" }.defaultInFeed)
    }

    @Test
    fun rankingBonusCanLiftAProfessionalRelease() {
        val ranker = Ranker()
        val studio = video("msav", "studio", "SSIS-123")
        val amateur = video("tmo", "amateur", "素人")
        val ranked = ranker.rank(
            candidates = listOf(amateur, studio),
            followedSlugs = emptySet(),
            affinity = emptyMap(),
            seen = emptySet(),
            bonus = { v -> if (v.sourceId == "msav") 10.0 else 0.0 }
        )
        assertEquals("studio", ranked.first().vkey)
    }

    @Test
    fun hugeModelIsNotMarkedLiveFriendly() {
        assertFalse(ModelCatalog.byId("large").liveFriendly)
        assertTrue(ModelCatalog.byId("base").liveFriendly)
        assertTrue(ModelCatalog.byId("small").liveFriendly)
    }

    @Test
    fun releaseIdentityKeepsCutsApart() {
        val normal = ContentFilters.releaseIdentity("SSIS-960 タイトル", "ssis-960")
        val mirror = ContentFilters.releaseIdentity("SSIS-960 タイトル", "ssis-960")
        assertEquals(normal, mirror)
        val subtitled = ContentFilters.releaseIdentity(
            "SSIS-960 タイトル english subtitle", "ssis-960-english-subtitle"
        )
        assertTrue(normal != subtitled)
        assertTrue(ContentFilters.releaseIdentity("no code here", "free-1") == null)
    }

    @Test
    fun tlsTrustErrorsBecomeActionable() {
        val root = CertPathValidatorException(
            "Trust anchor for certification path not found."
        )
        val handshake = SSLHandshakeException("Handshake failed")
        handshake.initCause(root)
        assertEquals(
            "TLS certificate not trusted (VPN/ad-block certificate?)",
            NetworkErrors.describe(handshake)
        )
    }
}
