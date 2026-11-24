package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.generic.JsPacker
import com.simplecalculator.app.data.source.generic.MissAvSource
import com.simplecalculator.app.data.source.generic.Titles
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixture tests for the MISSAV parser and the JS packer that hides its HLS URLs. */
class MissAvSourceTest {

    private val source = MissAvSource(OkHttpClient())

    private val listHtml: String by lazy { fixture("list.html") }
    private val detailHtml: String by lazy { fixture("detail.html") }
    private val liveDetailHtml: String by lazy { fixture("detail_live.html") }

    @Test
    fun sourceDeclaresJavCategory() {
        assertEquals("msav", source.id)
        assertEquals("MISSAV", source.displayName)
        assertEquals(Category.JAV, source.category)
    }

    @Test
    fun listFixtureParsesAtLeastTenCardsWithJapaneseVkeysAndAbsoluteThumbs() {
        val cards = source.parseListing(listHtml)

        assertTrue("expected >= 10 cards, got ${cards.size}", cards.size >= 10)
        assertEquals("cards must be deduped by vkey", cards.size, cards.map { it.vkey }.distinct().size)
        for (card in cards) {
            assertTrue("vkey must be a /ja/ path: $card", card.vkey.startsWith("/ja/"))
            assertTrue("vkey must carry a product code: $card", card.vkey.length > "/ja/".length)
            assertTrue("blank title in $card", card.title.isNotBlank())
            assertTrue(
                "title must not be a bare slug: $card",
                !card.title.equals(card.vkey.removePrefix("/ja/"), ignoreCase = true)
            )
            assertTrue("thumb must be absolute: $card", card.thumb.startsWith("https://"))
            assertEquals("msav", card.sourceId)
            assertEquals(Category.JAV, card.category)
        }
        assertTrue(
            "titles should be real text, not codes",
            cards.count { it.title.any { ch -> ch.code > 0x3000 } } >= 10
        )
        assertTrue("no durations parsed", cards.any { it.durationSec > 0 })
    }

    @Test
    fun detailFixtureUnpacksSurritHlsVariantsWithQualityLabels() {
        val layers = source.unpackPackers(detailHtml)
        assertTrue(
            "packed layer must contain the playlist URL, got $layers",
            layers.any { it.contains("source='https://surrit.com/") && it.contains(".m3u8") }
        )

        val streams = source.parseStreams(detailHtml).streams
        assertTrue("expected HLS streams, got $streams", streams.isNotEmpty())
        for (stream in streams) {
            assertTrue("not a surrit HLS URL: ${stream.url}", stream.url.contains("surrit.com"))
            assertTrue("not an m3u8 URL: ${stream.url}", stream.url.contains(".m3u8"))
            assertTrue("stream must be HLS: $stream", stream.hls)
        }
        assertTrue(
            "expected a 1080p label, got ${streams.map { it.label }}",
            streams.any { it.label == "1080p" }
        )
        assertTrue(
            "expected a 720p label, got ${streams.map { it.label }}",
            streams.any { it.label == "720p" }
        )
        assertEquals(streams.map { it.quality }.sortedDescending(), streams.map { it.quality })
    }

    @Test
    fun liveDetailFixtureUnpacksSurritHlsVariantsWithQualityLabels() {
        val streams = source.parseStreams(liveDetailHtml).streams
        assertTrue("expected HLS streams, got $streams", streams.isNotEmpty())
        assertTrue(
            "live detail must expose the surrit playlist: ${streams.map { it.url }}",
            streams.any { it.url.contains("surrit.com") && it.url.contains("playlist.m3u8") }
        )
        for (stream in streams) {
            assertTrue("not a surrit HLS URL: ${stream.url}", stream.url.contains("surrit.com"))
            assertTrue("not an m3u8 URL: ${stream.url}", stream.url.contains(".m3u8"))
            assertTrue("stream must be HLS: $stream", stream.hls)
        }
        assertTrue(
            "expected a 720p label, got ${streams.map { it.label }}",
            streams.any { it.label == "720p" }
        )
        assertEquals(streams.map { it.url }, streams.map { it.url }.distinct())
    }

    @Test
    fun titlesCleanStripsListingMetadata() {
        assertEquals(
            "素人妻の秘密",
            Titles.clean("HD 2K 7:19 124K 82% 1週間前 素人妻の秘密")
        )
        assertEquals(
            "本物のタイトル",
            Titles.clean("本物のタイトル 2:31:17 6.8K 91% 3日前")
        )
        assertEquals("Some Video Slug 123", Titles.clean("some-video-slug-123.html"))
        assertEquals("そのままのタイトル", Titles.clean("そのままのタイトル"))
    }

    @Test
    fun jsPackerDecodesSyntheticEvalPacker() {
        val packed = """eval(function(p,a,c,k,e,d){return p}('0://1/2.3',10,4,'https|cdn.example.com|720p/video|m3u8'.split('|'),0,{}))"""

        val decoded = JsPacker.unpackAll(packed)

        assertTrue("packed JS was not decoded: $decoded", decoded.contains("https://cdn.example.com/720p/video.m3u8"))
        assertTrue("empty layers must be dropped", JsPacker.unpackAll("<html>plain page</html>").isEmpty())
    }

    private fun fixture(name: String): String {
        val loader = MissAvSourceTest::class.java.classLoader ?: error("no class loader")
        val stream = loader.getResourceAsStream("missav/$name") ?: error("missing fixture missav/$name")
        return stream.use { it.bufferedReader(Charsets.UTF_8).readText() }
    }
}
