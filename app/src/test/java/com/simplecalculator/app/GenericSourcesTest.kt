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
    fun urlLikeTitlesAreHumanised() {
        val titles = com.simplecalculator.app.data.source.generic.Titles
        assertEquals(
            "Busting Asian Milf Dana Vespoli In Son",
            titles.clean("busting-asian-milf-dana-vespoli-in--son-99002041.html")
        )
        assertEquals("Some real title", titles.clean("Some real title"))
    }

    @Test
    fun subtitledDetectionDoesNotMatchRandomTitles() {
        val jav = com.simplecalculator.app.data.source.JavTitles
        assertTrue(!jav.isSubtitled("15378468wcpeeep 2枚目美人姉さん", "/video/7221237/x"))
        assertTrue(!jav.isSubtitled("50usd15383968wc It's smooth", "/video/7221233/x"))
        assertTrue(jav.isSubtitled("ABC-123 english subtitle", "/ja/abc-123"))
        assertTrue(jav.isSubtitled("作品名", "/ja/dsod-084-chinese-subtitle"))
    }

    @Test
    fun metadataPrefixesAreStrippedFromTitles() {
        assertEquals(
            "意外挿入、野外自転車上の乱倫",
            com.simplecalculator.app.data.source.generic.Titles.clean("2K 7:19 意外挿入、野外自転車上の乱倫")
        )
        assertEquals(
            "ライブ03",
            com.simplecalculator.app.data.source.generic.Titles.clean("HD 26:22 ライブ03 124K 82% 1週間前")
        )
        assertEquals(
            "Some real title",
            com.simplecalculator.app.data.source.generic.Titles.clean("4K 1:03:47 Some real title")
        )
        assertTrue(
            com.simplecalculator.app.data.source.generic.Titles.isMetadataOnly("1:03:47")
        )
        assertEquals(
            "細身 美乳大学生の手コキフェラ",
            com.simplecalculator.app.data.source.generic.Titles.clean(
                "2 videos 細身 美乳大学生の手コキフェラ 6,819Nov 17, 2026"
            )
        )
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
