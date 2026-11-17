package com.simplecalculator.app

import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.ListingKind
import com.simplecalculator.app.data.source.Source
import com.simplecalculator.app.data.source.SourceRegistry
import com.simplecalculator.app.data.source.generic.ExtraSites
import com.simplecalculator.app.data.source.generic.GenericHtmlSource
import com.simplecalculator.app.data.source.generic.MissAvSource
import com.simplecalculator.app.data.source.generic.InternetArchiveSource
import com.simplecalculator.app.data.source.hentai.AkiHSource
import com.simplecalculator.app.data.source.hentai.HentaiCloudSource
import com.simplecalculator.app.data.source.hentai.HentaiHavenSource
import com.simplecalculator.app.data.source.hentai.IwaraAiSource
import com.simplecalculator.app.data.source.hentai.IwaraSource
import com.simplecalculator.app.data.source.live.EpornerSource
import com.simplecalculator.app.data.source.live.HdsexSource
import com.simplecalculator.app.data.source.hentai.OhentaiSource
import com.simplecalculator.app.data.source.live.KvsSiteSource
import com.simplecalculator.app.data.source.live.YouJizzSource
import com.simplecalculator.app.data.source.live.XvideosSource
import com.simplecalculator.app.data.source.ph.PornhubSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Opt-in live probe: exercises every registered Source against the real site.
 * Run with LIVE_PROBE=1; normal CI stays offline.
 */
class LiveProbeTest {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun registry(): List<Source> = buildList {
        add(PornhubSource(http))
        add(XvideosSource(http))
        add(EpornerSource(http))
        add(KvsSiteSource(http, "ad", "AnaLdin", "https://www.analdin.com"))
        add(HdsexSource(http))
        add(YouJizzSource(http))
        ExtraSites.all.filter { it.category == Category.LIVE }
            .map { GenericHtmlSource(it, http) }.forEach(::add)
        add(MissAvSource(http))
        ExtraSites.all.filter { it.category == Category.JAV }
            .map { GenericHtmlSource(it, http) }.forEach(::add)
        add(AkiHSource(http))
        add(HentaiHavenSource(http))
        add(HentaiCloudSource(http))
        add(OhentaiSource(http))
        add(IwaraSource(http))
        add(IwaraAiSource(http))
        ExtraSites.all.filter { it.category == Category.HENTAI }
            .map { GenericHtmlSource(it, http) }.forEach(::add)
        add(InternetArchiveSource(http))
    }

    @Test
    fun akiHSearchExpandsToFullEpisodeList() = runBlocking {
        assumeTrue("set LIVE_PROBE=1 to run", System.getenv("LIVE_PROBE") == "1")
        val source = AkiHSource(http)
        val listing = source.listing(ListingKind.SEARCH, 1, "shoujo ramune", null)
        println("akih search: ${listing.size} cards")
        val series = listing.firstOrNull { it.title.contains("shoujo", true) }
        if (series == null) {
            println("akih search: no shoujo card")
            return@runBlocking
        }
        println("akih card label: " + series.episodeLabel + " key=" + series.vkey)
        val episodes = source.expandSeries(series)
        println("akih expand: ${episodes.size} episodes, labels=${episodes.map { it.episodeLabel }}")
        val groups = com.simplecalculator.app.data.source.SeriesGrouping.group(episodes)
        println("akih groups: ${groups.map { it.title + "=" + it.episodeCount }}")
        // The card the feed shows before expansion still carries the real count.
        println("akih card episodes: " + listing.firstOrNull { it.title.contains("shoujo", true) }?.episodeLabel)
        org.junit.Assert.assertEquals(7, episodes.size)
        val first = episodes.first()
        val detail = source.detail(first.vkey)
        println("akih detail: mode=${detail.playerMode} streams=${detail.streams.size} embed=${detail.embedUrl}")
    }

    @Test
    fun everySourceAnswersListingAndDetail() = runBlocking {
        assumeTrue("set LIVE_PROBE=1 to run", System.getenv("LIVE_PROBE") == "1")
        val only = System.getenv("LIVE_PROBE_IDS")?.split(',')?.toSet()
        val sources = registry().filter { only == null || it.id in only }
        println("probing ${sources.size} sources")
        var okListing = 0
        var okStream = 0
        for (source in sources) {
            val listing = runCatching {
                source.listing(ListingKind.TRENDING, 1, null, null)
            }
            val items = listing.getOrDefault(emptyList())
            val listingDetail = listing.exceptionOrNull()?.let { e ->
                (e.message ?: e::class.simpleName ?: "error").take(60)
            }
            var streamNote = "-"
            var hasStream = false
            val first = items.firstOrNull()
            if (first != null) {
                val detail = runCatching { source.detail(first.vkey) }
                val parsed = detail.getOrNull()
                hasStream = (parsed?.streams?.isNotEmpty() == true) || parsed?.embedUrl != null
                streamNote = when {
                    detail.isFailure -> "detail: " + (detail.exceptionOrNull()?.message ?: "error").take(70)
                    parsed?.embedUrl != null -> "embed"
                    else -> "${parsed?.streams?.size ?: 0} streams"
                }
            }
            if (items.isNotEmpty() && listingDetail != null) {
                // A listing that parses but always fails detail is still a bug.
                println("  detail error for ${source.id}/${first?.vkey}: ${listingDetail}")
            }
            if (items.isNotEmpty()) okListing++
            if (hasStream) okStream++
            println(
                String.format(
                    "%-6s %-14s items=%-4d %-30s %s",
                    source.id,
                    source.category.name.take(8),
                    items.size,
                    streamNote,
                    listingDetail?.let { "LISTING FAIL: $it" } ?: ""
                )
            )
        }
        println("summary: listing ok $okListing/${sources.size}, stream ok $okStream/${sources.size}")
    }
}
