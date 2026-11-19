package com.simplecalculator.app.data.stt

import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.db.SttCueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Transcripts are reusable: the same video opened twice, or a seek back, should
 * never pay for the same Whisper inference again. Cues are stored per video and
 * per language so a Turkish pass does not invalidate the English one.
 */
interface CueCache {
    suspend fun load(videoKey: String, lang: String): List<Caption>
    suspend fun save(videoKey: String, lang: String, cues: List<Caption>)
    suspend fun clear(videoKey: String)

    object None : CueCache {
        override suspend fun load(videoKey: String, lang: String): List<Caption> = emptyList()
        override suspend fun save(videoKey: String, lang: String, cues: List<Caption>) = Unit
        override suspend fun clear(videoKey: String) = Unit
    }
}

class RoomCueCache(private val db: AppDb) : CueCache {

    override suspend fun load(videoKey: String, lang: String): List<Caption> =
        withContext(Dispatchers.IO) {
            db.sttCues().forVideo(videoKey, lang).map { Caption(it.startMs, it.endMs, it.text) }
        }

    override suspend fun save(videoKey: String, lang: String, cues: List<Caption>) {
        if (cues.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            db.sttCues().putAll(
                cues.map {
                    SttCueEntity(
                        videoKey = videoKey,
                        lang = lang,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        text = it.text,
                        createdAt = now
                    )
                }
            )
        }
    }

    override suspend fun clear(videoKey: String) = withContext(Dispatchers.IO) {
        db.sttCues().clear(videoKey)
    }
}
