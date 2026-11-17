package com.simplecalculator.app

import com.simplecalculator.app.data.source.hentai.HentaiHavenSource
import com.simplecalculator.app.data.source.hentai.HentaiMamaSource
import com.simplecalculator.app.data.source.hentai.HentaiTvSource
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Runs the real listing parsers against saved copies of the live pages, so a
 * selector change shows up here instead of on a user's screen.
 */
class HentaiParserTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    private fun report(label: String, items: List<Pair<String, String>>) {
        println(label + ": " + items.size + " items")
        items.take(3).forEach { println("   " + it.first.take(48) + " | " + it.second.take(40)) }
    }

    @Test
    fun hentaihavenParsesCards() {
        val source = HentaiHavenSource(OkHttpClient())
        val items = source.parseListing(fixture("hh_home.html"))
        report("hh", items.map { it.title to it.thumb })
        assertTrue("no cards parsed", items.size >= 5)
        assertTrue("titles missing", items.all { it.title.length >= 2 })
        assertTrue("most thumbs missing", items.count { it.thumb.isNotBlank() } >= items.size / 2)
        assertTrue("cards must carry the watch path", items.all { it.vkey.startsWith("/watch/") })
    }

    @Test
    fun hentaiTvParsesCards() = runBlocking {
        val source = HentaiTvSource(OkHttpClient())
        val items = source.parseListing(fixture("htv_home.html"))
        report("htv", items.map { it.title to it.thumb })
        assertTrue("no cards parsed", items.size >= 5)
        assertTrue("titles missing", items.all { it.title.length >= 2 })
        assertTrue("cards must carry the series path", items.all { it.vkey.startsWith("/series/") })
    }

    @Test
    fun hentaiMamaParsesCards() {
        val source = HentaiMamaSource(OkHttpClient())
        val items = source.parseListing(fixture("hm_home.html"))
        report("hm", items.map { it.title to it.thumb })
        assertTrue("no cards parsed", items.size >= 5)
        assertTrue("titles missing", items.all { it.title.length >= 2 })
    }

    @Test
    fun hentaiHavenEpisodeExposesAStream() {
        val source = HentaiHavenSource(OkHttpClient())
        val streams = source.parseEpisodeStreams(fixture("hh_episode.html"))
        println("hh streams: " + streams.map { it.label + " " + it.url.take(70) })
        assertTrue("no stream parsed from episode page", streams.isNotEmpty())
        assertTrue("expected an HLS stream", streams.any { it.hls && it.url.contains("master.m3u8") })
    }

    @Test
    fun hentaiCloudLiveCaptureStillParses() {
        val source = com.simplecalculator.app.data.source.hentai.HentaiCloudSource(OkHttpClient())
        val items = source.parseListing(fixture("live/hc_list.html"))
        val streams = source.parseStreams(fixture("live/hc_detail.html"))
        println("hc live: ${items.size} cards, ${streams.size} streams")
        assertTrue("hc live listing empty", items.size >= 5)
        assertTrue("hc live streams empty", streams.isNotEmpty())
    }

    @Test
    fun hentaiCloudParsesListingAndStreams() {
        val source = com.simplecalculator.app.data.source.hentai.HentaiCloudSource(OkHttpClient())
        val items = source.parseListing(fixture("hc_list.html"))
        println("hc items: " + items.size + " sample=" + items.take(2).map { it.title.take(40) })
        assertTrue("no cards parsed", items.size >= 5)
        assertTrue("titles missing", items.all { it.title.length >= 2 })
        assertTrue("cards must be /video/ paths", items.all { it.vkey.startsWith("/video/") })

        val streams = source.parseStreams(fixture("hc_detail.html"))
        println("hc streams: " + streams.map { it.label + " " + it.url.takeLast(24) })
        assertTrue("no mp4 stream parsed", streams.isNotEmpty())
        assertTrue("expected an HD variant", streams.any { it.quality == 1080 })
    }

    @Test
    fun xvideosParsesListingAndStreams() {
        val source = com.simplecalculator.app.data.source.live.XvideosSource(OkHttpClient())
        val items = source.parseListing(fixture("xv_list.html"))
        println("xv items: " + items.size + " sample=" + items.take(2).map { it.title.take(40) })
        assertTrue("no cards", items.size >= 5)
        assertTrue("cards must be video paths", items.all { it.vkey.startsWith("/video") })

        val streams = source.parseStreams(fixture("xv_detail.html"))
        println("xv streams: " + streams.map { it.label })
        assertTrue("no stream", streams.isNotEmpty())
        assertTrue("expected mp4 and/or hls", streams.any { it.url.contains("mp4") || it.url.contains("m3u8") })
    }

    @Test
    fun epornerParsesListingAndStreams() {
        val source = com.simplecalculator.app.data.source.live.EpornerSource(OkHttpClient())
        val items = source.parseListing(fixture("ep_list.html"))
        println("ep items: " + items.size + " sample=" + items.take(2).map { it.title.take(40) })
        assertTrue("no cards", items.size >= 5)
        assertTrue("cards must be video paths", items.all { it.vkey.startsWith("/video-") })

        val streams = source.parseStreams(fixture("ep_detail.html"))
        println("ep streams: " + streams.map { it.label + " " + it.url.takeLast(30) })
        assertTrue("no stream", streams.isNotEmpty())
    }

    @Test
    fun iwaraParsesApiAndRenditions() {
        val source = com.simplecalculator.app.data.source.hentai.IwaraSource(OkHttpClient())
        val items = source.parseListing(fixture("iw_list.json"))
        println("iw items: " + items.size + " sample=" + items.take(2).map { it.title.take(30) })
        assertTrue("no items", items.size >= 5)
        assertTrue("ids missing", items.all { it.vkey.isNotBlank() })
        assertTrue("thumbs missing", items.any { it.thumb.startsWith("https://i.iwara.tv/") })

        val streams = source.parseRenditions(fixture("iw_renditions.json"))
        println("iw streams: " + streams.map { it.label })
        assertTrue("no rendition", streams.isNotEmpty())
        assertTrue("rendition url not absolute", streams.all { it.url.startsWith("https://") })
    }

    @Test
    fun blockedTitlesNeverReachTheUi() {
        val source = HentaiHavenSource(OkHttpClient())
        val items = source.parseListing(fixture("hh_home.html"))
        assertTrue(
            "a blocked title leaked through",
            items.none { it.title.lowercase().contains("loli") || it.title.lowercase().contains("shota") }
        )
    }
}
