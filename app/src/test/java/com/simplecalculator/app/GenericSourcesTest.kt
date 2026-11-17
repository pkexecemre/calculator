package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.generic.ExtraSites
import com.simplecalculator.app.data.source.generic.GenericHtmlSource
import com.simplecalculator.app.data.source.generic.SiteSpec
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericSourcesTest {

    private val spec = SiteSpec(
        id = "test",
        displayName = "Test",
        category = Category.LIVE,
        base = "https://example.com",
        searchPath = { q, _ -> "/search?q=$q" },
        cardSelector = ".item",
        titleSelector = "h3",
        thumbSelector = "img",
        durationSelector = ".dur"
    )
    private val source = GenericHtmlSource(spec, OkHttpClient())

    @Test
    fun parsesCardsWithResolvedUrlsAndDuration() {
        val html = """
            <div class="item">
              <a href="/video/1" title="Clip"><img src="/thumb.jpg"></a>
              <h3>Clip</h3><span class="dur">12:34</span>
            </div>
            <div class="item"><a href="/video/1"><img src="/thumb.jpg"></a><h3>Clip</h3></div>
        """.trimIndent()

        val items = source.parseListing(html, "https://example.com/")

        assertEquals(1, items.size) // duplicates are dropped by key
        assertEquals("test:/video/1", items[0].key)
        assertEquals("Clip", items[0].title)
        assertEquals("https://example.com/thumb.jpg", items[0].thumb)
        assertEquals(754, items[0].durationSec)
    }

    @Test
    fun parsesSourcesAndPlainMediaLinks() {
        val html = """
            <video src="https://cdn.example.com/a.mp4"></video>
            <source src="//cdn.example.com/b.m3u8">
            <script>window.url = "https:\/\/cdn.example.com\/c720p.mp4";</script>
        """.trimIndent()

        val detail = source.parseStreams(html, "https://example.com/video/1")

        assertEquals(3, detail.streams.size)
        assertTrue(detail.streams.any { it.hls && it.url == "https://cdn.example.com/b.m3u8" })
        assertTrue(detail.streams.any { it.quality == 720 })
    }

    @Test
    fun parsesJwPlayerFileField() {
        val html = """<script>jwplayer('p').setup({file: 'https:\/\/cdn.example.com\/movie720p.mp4'});</script>"""
        val detail = source.parseStreams(html, "https://example.com/watch")
        assertTrue(
            detail.streams.any { it.url == "https://cdn.example.com/movie720p.mp4" && it.quality == 720 }
        )
    }

    @Test
    fun parsesXHamsterStyleHlsCall() {
        val html = """<script>setVideoHLS('https://cdn.example.com/live/master.m3u8');</script>"""
        val detail = source.parseStreams(html, "https://jp.xhamster.com/videos/demo")
        assertTrue(detail.streams.any { it.hls && it.url == "https://cdn.example.com/live/master.m3u8" })
    }

    @Test
    fun extraSitesCoverTheMissPipeCatalog() {
        // Generic HTML sites; dedicated adapters cover Pornhub, XVideos, Eporner,
        // Avgle, YouJizz, AnaLdin, hdsex, MissAV, aki-h, OHentai, Iwara(.ai),
        // HentaiHaven, HentaiCloud and Internet Archive.
        assertEquals(14, ExtraSites.all.size)
        assertEquals(
            setOf(Category.LIVE, Category.JAV, Category.HENTAI),
            ExtraSites.all.map { it.category }.toSet()
        )
        assertTrue(ExtraSites.all.any { it.id == "h1" })
        assertTrue(ExtraSites.all.any { it.id == "r34" })
        assertTrue(ExtraSites.all.any { it.id == "hm" })
    }

    @Test
    fun searchPathIsBuiltFromTheSpec() {
        val recording = spec.copy(searchPath = { q, page -> "/find?q=$q&page=$page" })
        assertEquals("/find?q=jav&page=2", recording.searchPath("jav", 2))
    }
}
