package com.simplecalculator.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.simplecalculator.app.data.db.DownloadEntity
import com.simplecalculator.app.data.db.VideoEntity
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.screens.PlayerScreen
import com.simplecalculator.app.ui.theme.CalculatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Debug-only full player smoke test: inserts a local 11 s speech clip as a
 * "download" and opens the real PlayerScreen, so the audio tee, caption
 * overlay, pause flush and seek handling run exactly as in production.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugPlayerActivity
 */
class DebugPlayerActivity : ComponentActivity() {

    private var previousLiveSubs: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val container = (application as App).container
        val prefs = getSharedPreferences("calc_state", MODE_PRIVATE)
        previousLiveSubs = prefs.getString("live_subs", "off")
        val live = intent.getStringExtra("live") ?: "en"
        prefs.edit().putString("live_subs", live).apply()

        val key = KEY
        val file = File(filesDir, "debug-caption.wav")
        if (!file.exists() || file.length() < 1000) {
            assets.open("jfk.wav").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        runBlocking(Dispatchers.IO) {
            container.db.videos().upsert(
                listOf(
                    VideoEntity(
                        key = key,
                        sourceId = "debug",
                        vkey = "caption",
                        title = "Caption smoke test",
                        thumb = "",
                        durationSec = 11,
                        views = 0,
                        uploaderSlug = null,
                        uploaderName = null,
                        cachedAt = System.currentTimeMillis(),
                        category = "LIVE"
                    )
                )
            )
            // A previous run recorded progress; start this smoke test at 0.
            container.db.history().remove(key)
            container.db.downloads().upsert(
                DownloadEntity(
                    key = key,
                    vkey = "caption",
                    title = "Caption smoke test",
                    thumb = "",
                    filePath = file.absolutePath,
                    state = "done",
                    progress = 100,
                    quality = "local",
                    bytes = file.length(),
                    createdAt = System.currentTimeMillis()
                )
            )
        }

        setContent {
            CalculatorTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    PlayerScreen(
                        videoKey = key,
                        onBack = {},
                        onOpenVideo = {},
                        onOpenStar = {}
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        previousLiveSubs?.let { value ->
            getSharedPreferences("calc_state", MODE_PRIVATE)
                .edit().putString("live_subs", value).apply()
        }
        val container = (application as App).container
        runBlocking(Dispatchers.IO) {
            runCatching {
                container.db.downloads().remove(KEY)
                container.db.history().remove(KEY)
                container.db.videos().delete(KEY)
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val KEY = "debug:caption"
    }
}
