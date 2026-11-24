package com.simplecalculator.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.simplecalculator.app.data.source.Category
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Debug-only feed inspector for the two sources: prints each category's feed
 * and a search result with timing and error/stale state, without the vault.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugFeedActivity
 */
class DebugFeedActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    private lateinit var container: com.simplecalculator.app.Container
    private var lines = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 56, 24, 24)
            setBackgroundColor(Color.BLACK)
        }
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.START
        }
        root.addView(
            ScrollView(this).apply { addView(output) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        container = (application as App).container
        worker.execute {
            runBlocking(Dispatchers.IO) {
                showFeed(Category.LIVE, 30)
                showFeed(Category.JAV, 30)
                showSearch(Category.LIVE, "test")
                showSearch(Category.JAV, "SSIS")
            }
        }
    }

    private suspend fun showFeed(category: Category, limit: Int) {
        append("== FEED ${category.name} ==")
        val started = System.currentTimeMillis()
        val result = container.content.feed(category, 1)
        append(
            "items=" + result.items.size +
                " stale=" + result.stale +
                " error=" + (result.error ?: "none") +
                " elapsed=" + (System.currentTimeMillis() - started) + "ms"
        )
        for (video in result.items.take(limit)) {
            append("[" + video.sourceId + "] " + video.title.take(70))
        }
    }

    private suspend fun showSearch(category: Category, query: String) {
        append("== SEARCH ${category.name} $query ==")
        val started = System.currentTimeMillis()
        val result = container.content.search(category, query, 1)
        append(
            "items=" + result.items.size +
                " stale=" + result.stale +
                " error=" + (result.error ?: "none") +
                " elapsed=" + (System.currentTimeMillis() - started) + "ms"
        )
        for (video in result.items.take(10)) {
            append("[" + video.sourceId + "] " + video.title.take(70))
        }
    }

    private fun append(line: String) {
        Log.i("LocalFeedTest", line)
        handler.post {
            if (lines++ > 300) return@post
            output.append(line + "\n")
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
