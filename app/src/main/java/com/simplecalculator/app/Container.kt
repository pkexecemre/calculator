package com.simplecalculator.app

import android.content.Context
import com.simplecalculator.app.core.HiddenStore
import com.simplecalculator.app.core.PinStore
import com.simplecalculator.app.core.Translator
import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.download.DownloadsRepository
import com.simplecalculator.app.data.net.Ipv4Dns
import com.simplecalculator.app.data.rank.Ranker
import com.simplecalculator.app.data.repo.ContentRepository
import com.simplecalculator.app.data.source.SourceRegistry
import com.simplecalculator.app.data.source.generic.MissAvSource
import com.simplecalculator.app.data.source.ph.PhClient
import com.simplecalculator.app.data.source.ph.PornhubSource
import com.simplecalculator.app.data.stt.LiveTranscriber
import com.simplecalculator.app.data.stt.ModelStore
import com.simplecalculator.app.data.subtitle.Subtitles
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
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
        // Coil, Media3 and the page client share one pool. Every request needs
        // the browser UA and, for Pornhub, the age cookie or the CDN answers 403.
        .addInterceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
            val host = original.url.host.lowercase()
            if (original.header("User-Agent") == null) builder.header("User-Agent", PhClient.UA)
            if (original.header("Referer") == null) {
                refererFor(host)?.let { builder.header("Referer", it) }
            }
            if (host.endsWith("pornhub.com") && original.header("Cookie") == null) {
                builder.header("Cookie", PhClient.COOKIE)
            }
            chain.proceed(builder.build())
        }
        // Pornhub sends no-store/no-cache on pages and hotlinked thumbs. Storing
        // HTML/JSON in the disk cache makes it available when the network dies
        // mid-session; max-age=0 keeps normal requests revalidating so a manual
        // refresh is never answered by an old page.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val type = response.header("Content-Type").orEmpty()
            val cacheable = request.method == "GET" &&
                response.code == 200 &&
                (type.startsWith("text/html") || type.startsWith("application/json")) &&
                !request.url.encodedPath.contains("/get_media")
            if (!cacheable) {
                response
            } else {
                // Store the page but always revalidate: the manual refresh and
                // the pull-to-refresh path must never be answered by a stale
                // cache hit. The cache exists for the offline fallback below,
                // not to hide new content.
                val maxAge = if (request.url.encodedPath.contains("/comment")) 30 else 0
                response.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=$maxAge")
                    .build()
            }
        }
        // A dead host must fail fast on connect, while a slow-but-alive one gets
        // enough read room to finish. PhClient turns the whole request into a
        // bounded 25 s worst case.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .cache(Cache(File(appContext.cacheDir, "http"), 64L * 1024 * 1024))
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Headers every media request needs. The Referer is derived per request in
     * the interceptor: Pornhub's CDNs answer 403 when it is missing.
     */
    val mediaHeaders: Map<String, String> = mapOf(
        "User-Agent" to DESKTOP_UA,
        "Accept" to "*/*"
    )

    private fun refererFor(host: String): String? = when {
        host.endsWith("pornhub.com") || host.contains("phncdn") -> "https://www.pornhub.com/"
        host.contains("missav") || host.contains("surrit") || host.contains("fourhoi") ->
            "https://missav123.com/"
        else -> null
    }

    val db: AppDb = AppDb.build(appContext)

    val pinStore: PinStore = PinStore(appContext)

    val hidden: HiddenStore = HiddenStore(appContext)

    val translator: Translator = Translator(appContext, http)

    val subtitles: Subtitles = Subtitles(appContext, http, translator)

    val modelStore: ModelStore = ModelStore(appContext, http)

    val cueCache: com.simplecalculator.app.data.stt.CueCache =
        com.simplecalculator.app.data.stt.RoomCueCache(db)

    val transcriber: LiveTranscriber = LiveTranscriber(modelStore, cueCache)

    /** Decodes ahead of playback so captions are ready before they are spoken. */
    val lookahead: com.simplecalculator.app.data.stt.lookahead.LookaheadManager =
        com.simplecalculator.app.data.stt.lookahead.LookaheadManager(
            appContext, http, mediaHeaders, modelStore, cueCache
        )

    /** Exactly two sources: Pornhub (LIVE) and MISSAV (JAV). */
    val sources: SourceRegistry = SourceRegistry(
        listOf(PornhubSource(http), MissAvSource(http))
    )

    val ranker: Ranker = Ranker()

    val downloads: DownloadsRepository = DownloadsRepository(appContext, db, sources)

    val content: ContentRepository = ContentRepository(appContext, db, sources, ranker, hidden)
}
