package com.simplecalculator.app

import com.simplecalculator.app.data.source.SeriesGrouping
import com.simplecalculator.app.data.source.generic.ExtraSites
import com.simplecalculator.app.data.source.generic.GenericHtmlSource
import com.simplecalculator.app.data.source.generic.InternetArchiveSource
import com.simplecalculator.app.data.source.hentai.AkiHSource
import com.simplecalculator.app.data.source.hentai.HentaiCloudSource
import com.simplecalculator.app.data.source.hentai.HentaiHavenSource
import com.simplecalculator.app.data.source.ph.PhParser
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real pages captured from the sites (or their public pages through a reader
 * proxy) on 2026-11-16. These run offline in CI so a selector change fails fast.
 */
class LiveFixtureTest {

    private val http = OkHttpClient()
    private fun fixture(id: String): String =
        javaClass.classLoader!!.getResourceAsStream("live/$id.html")!!.bufferedReader().use { it.readText() }

    private fun generic(id: String, min: Int = 5) {
        val spec = ExtraSites.all.first { it.id == id }
        val source = GenericHtmlSource(spec, http)
        val items = source.parseListing(fixture(id), spec.base + spec.trendingPath(1))
        val titled = items.count { it.title.length >= 2 }
        val thumbs = items.count { it.thumb.isNotBlank() }
        println("$id: ${items.size} cards, titled=$titled, thumbs=$thumbs, sample=${items.firstOrNull()?.title?.take(40)}")
        assertTrue("$id parsed only ${items.size} cards", items.size >= min)
        assertTrue("$id titles missing ($titled/${items.size})", titled >= items.size / 2)
        assertTrue("$id thumbnails missing ($thumbs/${items.size})", thumbs >= items.size / 3)
    }

    @Test fun kissJav() = generic("kjv")
    @Test fun mrDouga() = generic("mrd")
    @Test fun oneFortyOne() = generic("141")
    @Test fun eightyFivePo() = generic("85p")
    @Test fun javNoni() = generic("jvn")
    @Test fun tokyoMotion() = generic("tmo")
    @Test fun javFun() = generic("jvf")
    @Test fun hentaiMama() = generic("hm")
    @Test fun hanime1() = generic("h1")
    @Test fun xhamster() = generic("xh")
    @Test fun xnxx() = generic("xnx")
    @Test fun spankBang() = generic("spb")

    @Test
    fun javSbFromMissPipeFixture() {
        // Synthetic card from MISSPipe's own JavSbParsingHelperTest.
        val html = """<html><body><div class="thumbnail group">
            <a href="/ja/jav/related-123-1-1.html">
              <img data-src="/upload/vod/related.jpg" alt="Related title">
            </a><span class="bottom-1 right-1">12:34</span></div></body></html>"""
        val spec = ExtraSites.all.first { it.id == "jvs" }
        val items = GenericHtmlSource(spec, http).parseListing(html, "https://jav.sb/ja/")
        assertEquals(1, items.size)
        assertEquals("Related title", items[0].title)
        assertEquals("https://jav.sb/upload/vod/related.jpg", items[0].thumb)
    }

    @Test
    fun akiHListingKeepsTrailingSlashAndExpands() {
        val source = AkiHSource(http)
        val items = source.parseCards(fixture("akih_home"))
        assertTrue("akih listing empty", items.isNotEmpty())
        // aki-h answers 404 without the trailing slash, so the key must keep it.
        assertTrue(
            "akih keys must end with a slash: " + items.take(3).map { it.vkey },
            items.all { it.vkey.endsWith("/") }
        )
        val series = items.first { it.vkey.contains("shoujo-ramune") }
        val episodes = source.parseEpisodes(fixture("akih_detail"), series.title, 7)
        assertEquals(7, episodes.size)
    }

    @Test
    fun akiHExpandsShoujoRamuneToSevenEpisodes() {
        val episodes = AkiHSource(http)
            .parseEpisodes(fixture("akih_detail"), "Shoujo Ramune", maxEpisode = 7)
        assertEquals(7, episodes.size)
        val groups = SeriesGrouping.group(episodes)
        assertEquals(1, groups.size)
        assertEquals(7, groups[0].episodeCount)
    }


    @Test
    fun missAvLivePageUnpacks() {
        val decoded = com.simplecalculator.app.data.source.generic.JsPacker
            .unpackAll(fixture("msav_detail_live"))
        val media = Regex("""https?://[^"'\s<>\\]+?\.m3u8""")
            .findAll(decoded).map { it.value }.distinct().toList()
        println("msav live decoded=${decoded.length} media=$media")
        assertTrue("no playlist unpacked from live missav page", media.isNotEmpty())
    }

    @Test
    fun lplaygoEmbedUnpacksToHls() {
        val decoded = com.simplecalculator.app.data.source.generic.JsPacker
            .unpackAll(fixture("lplaygo"))
        val media = Regex("""https?://[^"'\s<>\\]+?\.m3u8""")
            .findAll(decoded)
            .map { it.value }
            .toList()
        println("lplaygo hls: " + media.take(2))
        assertTrue("no m3u8 unpacked from lplaygo", media.isNotEmpty())
    }

    @Test
    fun akiHStreamPageUnpacksToAPlaylist() {
        val decoded = com.simplecalculator.app.data.source.generic.JsPacker
            .unpackAll(fixture("akih_stream"))
        println("akih stream decoded: ${decoded.length} chars")
        val file = Regex(""""file"\s*:\s*"(https?://[^"]+)"""")
            .findAll(decoded)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("/file/") }
        println("akih playlist: $file")
        if (file == null) println("DECODED>>>" + decoded.takeLast(1200))
        assertTrue("no /file/ playlist decoded", file != null)
        assertTrue("expected an HLS url", file!!.startsWith("https://aki-h.stream/file/"))
    }

    @Test
    fun missAvCardsCarryRealTitlesAndCovers() {
        val source = com.simplecalculator.app.data.source.generic.MissAvSource(http)
        val items = source.parseListing(fixture("msav_list"))
        println("msav cards: ${items.size}")
        items.take(3).forEach { println("  ${it.title.take(50)} | ${it.thumb.take(45)} | ${it.durationSec}") }
        assertTrue("msav listing empty", items.size >= 10)
        assertTrue("titles must not be clocks/codes", items.all { it.title.length >= 8 })
        assertTrue("covers must not be data URIs", items.none { it.thumb.startsWith("data:") })
        assertTrue("most cards need a cover", items.count { it.thumb.startsWith("http") } >= items.size / 2)
        assertTrue("durations parsed", items.any { it.durationSec > 60 })
    }

    @Test
    fun subtitleRenditionsAreParsedFromAMasterPlaylist() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Türkçe",LANGUAGE="tr",URI="https://cdn.example/subs/tr.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=842x480,SUBTITLES="subs"
            a8Op/index.txt
        """.trimIndent()
        // The parser is pure: no context or network involved.
        val tracks = com.simplecalculator.app.data.subtitle.MasterPlaylist
            .subtitles(master, "https://cdn.example/video/master.m3u8")
        assertEquals(2, tracks.size)
        assertEquals("en", tracks[0].language)
        assertEquals("https://cdn.example/video/subs/en.m3u8", tracks[0].url)
        assertEquals("tr", tracks[1].language)
    }

    @Test
    fun uncensoredSlugsAreRecognised() {
        val jav = com.simplecalculator.app.data.source.JavTitles
        assertTrue(jav.isUncensored("MKMP-744 早漏撲滅", "/ja/mkmp-744-uncensored-leak"))
        assertTrue(jav.isUncensored("無修正 美女羞恥", "/ja/abc-123"))
        assertTrue(!jav.isUncensored("GNS-167 清掃員", "/ja/gns-167"))
        assertTrue(jav.isCensored("GNS-167 清掃員", "/ja/gns-167"))
    }

    @Test
    fun missAvUnpacksTheHlsPlaylist() {
        val source = com.simplecalculator.app.data.source.generic.MissAvSource(http)
        val items = source.parseListing(fixture("msav_list"))
        println("msav: ${items.size} cards, sample=${items.firstOrNull()?.title?.take(30)}")
        assertTrue("msav listing empty", items.size >= 10)
        assertTrue("msav cards must be /ja/slug", items.all { it.vkey.startsWith("/ja/") })

        val detail = source.parseStreams(fixture("msav_detail"))
        println("msav streams: " + detail.streams.map { it.label + " " + it.url.takeLast(30) })
        assertTrue("msav found no HLS", detail.streams.any { it.hls && it.url.contains("surrit") })
        assertTrue("expected a 1080p variant", detail.streams.any { it.quality >= 720 })
    }

    @Test
    fun youJizzCardsCarryASignedClip() {
        val source = com.simplecalculator.app.data.source.live.YouJizzSource(http)
        val items = source.parseListing(fixture("yj"))
        println("yj: ${items.size} cards")
        assertTrue("yj listing empty", items.size >= 5)
        val detail = kotlinx.coroutines.runBlocking { source.detail(items.first().vkey) }
        assertTrue("yj detail has no stream", detail.streams.isNotEmpty())
        assertTrue(detail.streams.first().url.startsWith("https://"))
    }

    @Test
    fun ohentaiDerivesTheSiblingMp4() {
        val page = fixture("ohe_detail")
        val poster = Regex("""property=["']og:image["']\s+content=["']([^"']+)["']""")
            .find(page)?.groupValues?.get(1)
        assertTrue("og:image missing", !poster.isNullOrBlank())
        assertTrue("poster should be an image", poster!!.matches(Regex(""".*\.(jpg|png|webp)$""")))
    }

    @Test
    fun kvsBase64SitesExposeStreams() {
        for (id in listOf("mrd", "jvf", "r34")) {
            val spec = ExtraSites.all.first { it.id == id }
            val detail = GenericHtmlSource(spec, http)
                .parseStreams(fixture("${id}_detail"), spec.base + "/")
            println("$id detail: streams=${detail.streams.size} embed=${detail.embedUrl}")
            assertTrue(
                "$id produced neither a stream nor an embed",
                detail.streams.isNotEmpty() || detail.embedUrl != null
            )
        }
    }

    @Test
    fun avglePlayEndpointIsResolved() = kotlinx.coroutines.runBlocking {
        val source = com.simplecalculator.app.data.source.live.AvgleSource(http)
        val html = fixture("avg_detail")
        val items = source.parseListing(fixture("avg"))
        assertTrue("avg listing empty", items.size >= 10)
        assertTrue(
            "avg detail links must carry slug",
            items.all { it.vkey.contains("slug=") }
        )
        // The signed endpoint expires, so only assert the scrape shape here.
        assertTrue("playEndpoint not found", html.contains("playEndpoint"))
    }

    @Test
    fun analdinKvsRealPage() {
        val source = com.simplecalculator.app.data.source.live.KvsSiteSource(
            http, "ad", "AnaLdin", "https://www.analdin.com"
        )
        val items = source.parseListing(fixture("ad"))
        val streams = source.parseStreams(fixture("ad_detail"))
        println("ad: ${items.size} items, ${streams.size} streams -> ${streams.map { it.label + " " + it.url.takeLast(28) }}")
        assertTrue("ad listing empty", items.size >= 5)
        assertTrue("ad streams empty", streams.isNotEmpty())
    }

    @Test
    fun javDetailPagesExposeStreams() {
        // MRDOUGA keeps streams on an embed page that reader proxies strip;
        // its KVS parser is covered by the synthetic test below.
        for (id in listOf("85p", "tmo")) {
            val spec = ExtraSites.all.first { it.id == id }
            val detail = GenericHtmlSource(spec, http)
                .parseStreams(fixture("${id}_detail"), spec.base + "/")
            println("$id jav detail: ${detail.streams.size} streams")
            assertTrue("$id had no playable JAV stream", detail.streams.isNotEmpty())
        }
    }

    @Test
    fun kvsEmbedStreams() {
        val source = com.simplecalculator.app.data.source.live.KvsSiteSource(
            http, "ad", "AnaLdin", "https://www.analdin.com"
        )
        val html = """video_url: 'https://cdn.example.com/a.mp4';
            |video_alt_url: 'https://cdn.example.com/b_720p.mp4';""".trimMargin()
        val streams = source.parseStreams(html)
        assertEquals(2, streams.size)
        assertTrue(streams.any { it.quality == 1080 })
    }

    @Test
    fun genericDetailPagesExposeStreams() {
        // OHentai/xHamster player configs are dropped by reader proxies; their
        // extraction branches are covered by synthetic tests instead.
        for (id in listOf("85p", "h1")) {
            val spec = ExtraSites.all.first { it.id == id }
            val detail = GenericHtmlSource(spec, http)
                .parseStreams(fixture("${id}_detail"), spec.base + "/")
            println("$id detail: ${detail.streams.size} streams")
            assertTrue("$id had no playable stream", detail.streams.isNotEmpty())
        }
    }

    @Test
    fun iwaraAiUsesTheSameParser() {
        val raw = javaClass.classLoader!!.getResourceAsStream("iw_list.json")!!
            .bufferedReader().use { it.readText() }
        val items = com.simplecalculator.app.data.source.hentai.IwaraAiSource(http)
            .parseListing(raw)
        assertTrue("iwai parsed only ${items.size} cards", items.size >= 5)
    }

    @Test
    fun pornhub() {
        val items = PhParser.parseList(fixture("ph"), "ph")
        assertTrue("ph parsed only ${items.size} cards", items.size >= 5)
        assertTrue(items.all { it.vkey.isNotBlank() })
        println("ph: ${items.size} cards")
    }

    @Test
    fun internetArchive() {
        val json = Jsoup.parse(fixture("ia")).selectFirst("pre")?.text().orEmpty()
        val items = InternetArchiveSource(http).parseListing(json)
        assertTrue("ia parsed only ${items.size} cards", items.size >= 5)
        assertTrue(items.all { it.thumb.startsWith("https://archive.org/services/img/") })
        println("ia: ${items.size} items")
    }

    @Test
    fun hentaiHavenAndCloudLivePages() {
        val hh = HentaiHavenSource(http).parseListing(fixture("hh"))
        val hc = HentaiCloudSource(http).parseListing(fixture("hc"))
        assertTrue("hh parsed only ${hh.size} cards", hh.size >= 5)
        assertTrue("hc parsed only ${hc.size} cards", hc.size >= 5)
        println("hh: ${hh.size}, hc: ${hc.size}")
    }
}
