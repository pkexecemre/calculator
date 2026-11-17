package com.simplecalculator.app.data.subtitle

import android.content.Context
import com.simplecalculator.app.core.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** A side-loaded subtitle track the player can attach to the current stream. */
data class SubtitleTrack(val language: String, val label: String, val url: String)

/**
 * Subtitles that the site already publishes: HLS `EXT-X-MEDIA:TYPE=SUBTITLES`
 * renditions. Sites without them return an empty list — nothing is invented.
 */
class Subtitles(
    private val context: Context,
    private val http: OkHttpClient,
    private val translator: Translator
) {

    suspend fun forStream(streamUrl: String, referer: String?): List<SubtitleTrack> =
        withContext(Dispatchers.IO) {
            if (!streamUrl.contains(".m3u8")) return@withContext emptyList()
            val master = runCatching { get(streamUrl, referer) }.getOrNull()
                ?: return@withContext emptyList()
            parseMaster(master, streamUrl)
        }

    internal fun parseMaster(master: String, masterUrl: String): List<SubtitleTrack> =
        MasterPlaylist.subtitles(master, masterUrl)

    /**
     * Downloads a track and rewrites it in the requested language. Translation is
     * per cue and cached by the translator, so a second watch is instant.
     */
    suspend fun translated(track: SubtitleTrack, language: String, referer: String?): File? =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "subs").apply { mkdirs() }
            val target = File(cacheDir, "${track.url.hashCode()}-$language.vtt")
            if (target.exists() && target.length() > 0) return@withContext target

            val raw = runCatching { get(track.url, referer) }.getOrNull() ?: return@withContext null
            val cues = Cues.parse(raw)
            if (cues.isEmpty()) return@withContext null

            val translated = coroutineScope {
                cues.map { cue ->
                    async {
                        if (language == "en") cue.text else translator.translate(cue.text) ?: cue.text
                    }
                }.map { it.await() }
            }
            target.writeText(Cues.toVtt(cues.map { it.start to it.end }, translated))
            target
        }

    private fun get(url: String, referer: String?): String {
        val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
        referer?.let { builder.header("Referer", it) }
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.string() ?: ""
        }
    }
}

internal data class Cue(val start: String, val end: String, val text: String)

/** Minimal VTT/SRT parsing: timings are kept, text is what gets translated. */
internal object Cues {

    private val timing = Regex("""(\d{1,2}:\d{2}:\d{2}[.,]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{3})""")

    fun parse(raw: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = raw.replace("\r", "").split("\n")
        var index = 0
        while (index < lines.size) {
            val match = timing.find(lines[index])
            if (match == null) {
                index++
                continue
            }
            val start = match.groupValues[1].replace(',', '.')
            val end = match.groupValues[2].replace(',', '.')
            index++
            val text = StringBuilder()
            while (index < lines.size && lines[index].isNotBlank() && timing.find(lines[index]) == null) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(lines[index].trim())
                index++
            }
            val clean = text.toString().replace(Regex("<[^>]+>"), "").trim()
            if (clean.isNotEmpty()) out.add(Cue(start, end, clean))
            if (out.size >= MAX_CUES) break
        }
        return out
    }

    fun toVtt(timings: List<Pair<String, String>>, texts: List<String>): String = buildString {
        append("WEBVTT\n\n")
        timings.forEachIndexed { index, (start, end) ->
            append(index + 1).append('\n')
            append(start).append(" --> ").append(end).append('\n')
            append(texts.getOrElse(index) { "" }).append("\n\n")
        }
    }

    private const val MAX_CUES = 600
}

/** Pure parser, kept separate so it is testable without a Context. */
object MasterPlaylist {
    fun subtitles(master: String, masterUrl: String): List<SubtitleTrack> {
        val tracks = ArrayList<SubtitleTrack>()
        val base = masterUrl.substringBeforeLast('/') + "/"
        for (line in master.lineSequence()) {
            if (!line.startsWith("#EXT-X-MEDIA")) continue
            if (!line.contains("TYPE=SUBTITLES", ignoreCase = true)) continue
            val uri = attribute(line, "URI") ?: continue
            val language = attribute(line, "LANGUAGE") ?: "und"
            val name = attribute(line, "NAME") ?: language.uppercase()
            val absolute = if (uri.startsWith("http")) uri else base + uri.removePrefix("./")
            tracks.add(SubtitleTrack(language.lowercase(), name, absolute))
        }
        return tracks
    }

    private fun attribute(line: String, key: String): String? {
        val marker = "$key="
        val start = line.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val rest = line.substring(start + marker.length)
        val quote = rest.firstOrNull()
        return if (quote == '"') rest.substringAfter('"').substringBefore('"') else rest.substringBefore(',')
    }
}
