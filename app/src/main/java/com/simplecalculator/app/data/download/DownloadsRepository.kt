package com.simplecalculator.app.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.simplecalculator.app.data.db.AppDb
import com.simplecalculator.app.data.db.DownloadEntity
import com.simplecalculator.app.data.db.VideoEntity
import com.simplecalculator.app.data.source.SourceRegistry
import kotlinx.coroutines.flow.Flow
import java.io.File

class DownloadsRepository(
    private val context: Context,
    private val db: AppDb,
    private val sources: SourceRegistry
) {

    fun observe(): Flow<List<DownloadEntity>> = db.downloads().observeAll()

    suspend fun enqueue(video: VideoEntity, qualityLabel: String? = null) {
        val existing = db.downloads().byKey(video.key)
        if (existing != null && (existing.state == "running" || existing.state == "done")) return

        db.downloads().upsert(
            DownloadEntity(
                key = video.key,
                vkey = video.vkey,
                title = video.title,
                thumb = video.thumb,
                filePath = null,
                state = "queued",
                progress = 0,
                quality = null,
                bytes = 0,
                createdAt = System.currentTimeMillis()
            )
        )

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    "key" to video.key,
                    "vkey" to video.vkey,
                    "sourceId" to video.sourceId,
                    "title" to video.title,
                    "thumb" to video.thumb,
                    "qualityLabel" to (qualityLabel ?: "")
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork("dl:" + video.key, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun cancel(item: DownloadEntity) {
        WorkManager.getInstance(context).cancelUniqueWork("dl:" + item.key)
        // The worker writes to a .part file first; clean up both final and temp
        // names so a cancelled run cannot leave an orphan or a half-written file.
        val stem = item.key.replace(":", "_")
        val dir = File(context.getExternalFilesDir(null), "downloads")
        listOf(
            File(dir, "$stem.mp4"),
            File(dir, "$stem.ts"),
            File(dir, "$stem.mp4.part"),
            File(dir, "$stem.ts.part")
        ).forEach { runCatching { it.delete() } }
        item.filePath?.let { path -> runCatching { File(path).delete() } }
        db.downloads().remove(item.key)
    }

    suspend fun retry(item: DownloadEntity) {
        val video = db.videos().byKey(item.key) ?: return
        WorkManager.getInstance(context).cancelUniqueWork("dl:" + item.key)
        db.downloads().remove(item.key)
        enqueue(video)
    }

    suspend fun localFile(key: String): File? {
        val row = db.downloads().byKey(key) ?: return null
        val path = row.filePath ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }
}
