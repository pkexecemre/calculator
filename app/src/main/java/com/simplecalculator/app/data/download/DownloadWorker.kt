package com.simplecalculator.app.data.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.simplecalculator.app.App
import com.simplecalculator.app.data.source.StreamVariant
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Resolves the signed stream URL at download time (they expire in hours), then
 * pulls either the progressive MP4 or, failing that, every HLS segment joined
 * into one transport stream. Media3 plays the result straight from disk.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val db = container.db
        val http = container.http

        val key = inputData.getString("key") ?: return Result.failure()
        val vkey = inputData.getString("vkey") ?: return Result.failure()
        val sourceId = inputData.getString("sourceId") ?: return Result.failure()

        val source = container.sources.byId(sourceId) ?: return Result.failure()
        var part: File? = null

        try {
            db.downloads().markRunning(key)
            val detail = source.detail(vkey)
            val stream = pickStream(detail.streams, inputData.getString("qualityLabel"))
                ?: throw IOException("no playable stream")

            val dir = File(applicationContext.getExternalFilesDir(null), "downloads")
            if (!dir.exists()) dir.mkdirs()
            // App-specific external storage is not indexed, but keep old
            // gallery scanners out explicitly.
            File(dir, ".nomedia").createNewFile()
            val stem = key.replace(":", "_")
            val target = File(dir, stem + if (stream.hls) ".ts" else ".mp4")
            part = File(target.path + ".part")
            if (target.exists()) target.delete()

            val bytes = if (stream.hls) {
                downloadHls(http, stream.url, part) { percent ->
                    db.downloads().updateProgress(key, percent, "running")
                }
            } else {
                downloadMp4(http, stream.url, part) { percent ->
                    db.downloads().updateProgress(key, percent, "running")
                }
            }

            if (bytes < 4096L) throw IOException("download produced " + bytes + " bytes")
            if (!part.renameTo(target)) throw IOException("could not finalize download")
            db.downloads().markDone(key, "done", target.absolutePath, bytes)
            part = null
            return Result.success()
        } catch (e: Exception) {
            part?.delete()
            if (isStopped) {
                // Cancelled by the user: leave no row and no temp file behind.
                db.downloads().remove(key)
                return Result.failure()
            }
            Log.w(TAG, "download failed for " + key, e)
            db.downloads().markFailed(key, e.message?.take(160))
            return Result.failure()
        }
    }

    /**
     * Downloads cap at 720p unless that is unavailable: a 1080p adult video runs
     * to several hundred megabytes and most of them are watched on a phone screen.
     */
    private fun pickStream(
        streams: List<StreamVariant>,
        requestedLabel: String? = null
    ): StreamVariant? {
        if (!requestedLabel.isNullOrBlank()) {
            streams.firstOrNull { it.label == requestedLabel }?.let { return it }
        }
        val capped = streams.filter { it.quality in 1..720 }
        val pool = if (capped.isNotEmpty()) capped else streams
        val directMp4 = pool
            .filter { !it.hls && it.quality > 0 && it.url.contains(".mp4") }
            .maxByOrNull { it.quality }
        if (directMp4 != null) return directMp4
        return pool.filter { it.hls }.maxByOrNull { it.quality }
    }

    private suspend fun downloadMp4(
        http: OkHttpClient,
        url: String,
        target: File,
        onProgress: suspend (Int) -> Unit
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            var written = 0L
            val buffer = ByteArray(64 * 1024)
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        if (isStopped) throw IOException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written * 100 / total).toInt().coerceIn(0, 99))
                    }
                }
            }
            written
        }
    }

    private suspend fun downloadHls(
        http: OkHttpClient,
        url: String,
        target: File,
        onProgress: suspend (Int) -> Unit
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val masterUrl = url.toHttpUrlOrNull() ?: throw IOException("bad url")
        var playlistUrl = masterUrl
        var text = fetchText(http, playlistUrl.toString())

        if (text.contains("EXT-X-STREAM-INF")) {
            val variantLine = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .lastOrNull() ?: throw IOException("no variant")
            val resolved = masterUrl.resolve(variantLine) ?: throw IOException("bad variant url")
            playlistUrl = resolved
            text = fetchText(http, resolved.toString())
        }

        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val segments = ArrayList<String>()
        val initSegments = ArrayList<String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MAP")) {
                val uri = attributeValue(line, "URI")
                if (uri != null) initSegments.add(uri)
            } else if (!line.startsWith("#")) {
                segments.add(line)
            }
        }
        if (segments.isEmpty()) throw IOException("playlist has no segments")

        var written = 0L
        var done = 0
        val total = segments.size + initSegments.size
        target.outputStream().use { out ->
            for (init in initSegments) {
                if (isStopped) throw IOException("cancelled")
                written += append(http, playlistUrl.resolve(init), out)
                done++
                onProgress((done * 100 / total).coerceIn(0, 99))
            }
            for (segment in segments) {
                if (isStopped) throw IOException("cancelled")
                written += append(http, playlistUrl.resolve(segment), out)
                done++
                onProgress((done * 100 / total).coerceIn(0, 99))
            }
        }
        written
    }

    private fun append(
        http: OkHttpClient,
        url: okhttp3.HttpUrl?,
        out: java.io.OutputStream
    ): Long {
        val target = url ?: throw IOException("unresolved segment")
        val request = Request.Builder()
            .url(target)
            .header("User-Agent", UA)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            val body = response.body ?: throw IOException("empty segment")
            return body.byteStream().use { input -> input.copyTo(out, 64 * 1024) }
        }
    }

    private fun fetchText(http: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP " + response.code)
            return response.body?.string() ?: ""
        }
    }

    private fun attributeValue(line: String, key: String): String? {
        val marker = key + "="
        val start = line.indexOf(marker)
        if (start < 0) return null
        val rest = line.substring(start + marker.length)
        val quote = rest.firstOrNull()
        return if (quote == '"') rest.substringAfter('"').substringBefore('"') else rest.substringBefore(',')
    }

    companion object {
        private const val TAG = "DownloadWorker"
        private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
