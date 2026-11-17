package com.simplecalculator.app

import android.content.Context
import com.simplecalculator.app.core.HiddenStore
import com.simplecalculator.app.core.PinStore
import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.download.DownloadsRepository
import com.simplecalculator.app.data.net.Ipv4Dns
import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.repo.ContentRepository
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.data.source.SharedPreferencesHealthStore
import com.simplecalculator.app.data.source.SourceHealth
import com.simplecalculator.app.data.source.SourceRegistry
import com.simplecalculator.app.data.source.generic.ExtraSites
import com.simplecalculator.app.data.source.generic.GenericHtmlSource
import com.simplecalculator.app.data.source.generic.InternetArchiveSource
import com.simplecalculator.app.data.source.generic.MissAvSource
import com.simplecalculator.app.data.source.hentai.AkiHSource
import com.simplecalculator.app.data.source.hentai.HentaiCloudSource
import com.simplecalculator.app.data.source.hentai.HentaiHavenSource
import com.simplecalculator.app.data.source.hentai.IwaraAiSource
import com.simplecalculator.app.data.source.hentai.IwaraSource
import com.simplecalculator.app.data.source.hentai.OhentaiSource
import com.simplecalculator.app.data.source.live.AvgleSource
import com.simplecalculator.app.data.source.live.EpornerSource
import com.simplecalculator.app.data.source.live.HdsexSource
import com.simplecalculator.app.data.source.live.KvsSiteSource
import com.simplecalculator.app.data.source.live.YouJizzSource
import com.simplecalculator.app.data.source.live.XvideosSource
import com.simplecalculator.app.data.source.ph.PhClient
import com.simplecalculator.app.data.source.ph.PornhubSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class Container(context: Context) {

    companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    val appContext: Context = context.applicationContext

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(Ipv4Dns())
        // Coil and the media stack both hit the same CDNs; every request needs the
        // age cookie, a browser UA and a referer or the image hosts answer 403.
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            val host = original.url.host.lowercase()
            if (original.header("User-Agent") == null) builder.header("User-Agent", PhClient.UA)
            if (original.header("Referer") == null) {
                refererFor(host)?.let { builder.header("Referer", it) }
            }
            // Do not leak the age cookie to unrelated third-party hosts.
            if (host.endsWith("pornhub.com") && original.header("Cookie") == null) {
                builder.header("Cookie", "age_verified=1; platform=pc")
            }
            chain.proceed(builder.build())
        }
        // Slow-but-alive connections deserve patience on read and a fast fail on
        // connect, so a dead host does not hold a feed slot for half a minute.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .cache(okhttp3.Cache(java.io.File(appContext.cacheDir, "http"), 64L * 1024 * 1024))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Headers every media request needs. The Referer is derived per request in the
     * interceptor: CDNs answer 403 when it does not match their own site, and some
     * (hdsex.org) reject the Android UA outright on HLS endpoints.
     */
    val mediaHeaders: Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "*/*"
    )

    /**
     * Thumbnail CDNs hotlink-protect by Referer: the value has to be the *site*,
     * not the CDN host. Without this map most covers render as empty boxes.
     */
    private fun refererFor(host: String): String? = when {
        host.endsWith("pornhub.com") || host.contains("phncdn") -> "https://www.pornhub.com/"
        host.contains("xvideos") || host.contains("xvcdn") || host.contains("cdn77") ->
            "https://www.xvideos.com/"
        host.contains("eporner") || host.contains("gvideo") -> "https://www.eporner.com/"
        host.contains("hdsex") -> "https://hdsex.org/"
        host.contains("analdin") || host.contains("cdnhop") -> "https://www.analdin.com/"
        host.contains("hentaihaven") || host.contains("master-lengs") -> "https://hentaihaven.xxx/"
        host.contains("hentaicloud") -> "https://www.hentaicloud.com/"
        host.contains("iwara") -> "https://www.iwara.tv/"
        host.contains("missav") || host.contains("surrit") -> "https://missav123.com/"
        // fourhoi serves missav covers and answers 403 to its own host as Referer.
        host.contains("fourhoi") -> "https://missav123.com/"
        host.contains("aki-h") || host.contains("aki.today") || host.contains("tnmr.org") ->
            "https://aki-h.com/"
        host.contains("hanime1") -> "https://hanime1.me/"
        host.contains("ohentai") || host.contains("logivanta") -> "https://ohentai.org/"
        host.contains("rule34video") || host.contains("r34") -> "https://rule34video.com/"
        host.contains("hentaimama") -> "https://hentaimama.io/"
        host.contains("tokyomotion") -> "https://www.tokyomotion.net/"
        host.contains("spankbang") -> "https://www.spankbang.com/"
        host.contains("xhamster") || host.contains("xhcdn") -> "https://jp.xhamster.com/"
        host.contains("xnxx") || host.contains("xncdn") -> "https://www.xnxx.com/"
        host.contains("youjizz") -> "https://www.youjizz.com/"
        host.contains("85po") -> "https://www.85po.com/"
        host.contains("kissjav") -> "https://kissjav.li/"
        host.contains("javnoni") -> "https://jav-noni.vip/"
        host.contains("jav.sb") -> "https://jav.sb/"
        host.contains("mrdouga") -> "https://mrdouga.com/"
        host.contains("jav-fun") || host.contains("lulustream") || host.contains("lplaygo") ->
            "https://jav-fun.cc/"
        host.contains("141tube") -> "https://141tube.com/"
        host.contains("avgle") -> "https://avgle.net/"
        host.contains("archive.org") -> "https://archive.org/"
        else -> null
    }

    val db: AppDb = AppDb.build(appContext)

    val pinStore: PinStore = PinStore(appContext)

    val hidden: HiddenStore = HiddenStore(appContext)

    val sources: SourceRegistry = SourceRegistry(
        buildList {
            // LIVE — primary stays first because follows/watch affinity use it.
            add(PornhubSource(http))
            add(XvideosSource(http))
            add(EpornerSource(http))
            add(AvgleSource(http))
            add(KvsSiteSource(http, "ad", "AnaLdin", "https://www.analdin.com"))
            add(HdsexSource(http))
            add(YouJizzSource(http))
            ExtraSites.all.filter { it.category == Category.LIVE }
                .map { GenericHtmlSource(it, http) }.forEach(::add)

            // JAV
            add(MissAvSource(http))
            ExtraSites.all.filter { it.category == Category.JAV }
                .map { GenericHtmlSource(it, http) }.forEach(::add)

            // HENTAI
            add(AkiHSource(http))
            add(HentaiHavenSource(http))
            add(HentaiCloudSource(http))
            add(OhentaiSource(http))
            add(IwaraSource(http))
            add(IwaraAiSource(http))
            ExtraSites.all.filter { it.category == Category.HENTAI }
                .map { GenericHtmlSource(it, http) }.forEach(::add)

            // ARCHIVE
            add(InternetArchiveSource(http))
        }
    )

    val ranker: Ranker = Ranker()

    val downloads: DownloadsRepository = DownloadsRepository(appContext, db, sources)

    val health: SourceHealth = SourceHealth(SharedPreferencesHealthStore(appContext))

    val content: ContentRepository = ContentRepository(db, sources, ranker, health, hidden)
}
