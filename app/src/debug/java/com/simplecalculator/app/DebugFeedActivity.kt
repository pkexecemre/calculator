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
import com.simplecalculator.app.data.source.ContentFilters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/**
 * Debug-only feed inspector: prints the merged Live and JAV feeds with their
 * source and title so the Western/quality policy can be checked on a device.
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
                container.health.resetAll(container.sources.all())
                show(Category.LIVE, sourceFilter = null, limit = 24)
                show(Category.JAV, sourceFilter = null, limit = 30)
                show(Category.LIVE, sourceFilter = "avg", limit = 10)
                showSearch("SSIS")
                showSearch("SSIS")
            }
        }
    }

    private suspend fun showSearch(query: String) {
        append("== SEARCH ${query} ==")
        val started = System.currentTimeMillis()
        var firstMs = -1L
        var updates = 0
        var finalSize = 0
        runCatching {
            container.content.searchStream(Category.JAV, query).collect { update ->
                updates++
                if (firstMs < 0 && update.items.isNotEmpty()) {
                    firstMs = System.currentTimeMillis() - started
                }
                if (update.done) {
                    finalSize = update.items.size
                    append(
                        "search first=" + firstMs + "ms total=" +
                            (System.currentTimeMillis() - started) + "ms updates=" +
                            updates + " items=" + finalSize
                    )
                }
            }
        }.onFailure { append("search failed: " + it.message) }
        if (firstMs < 0) {
            append("search first=none total=" + (System.currentTimeMillis() - started) + "ms")
        }
    }

    private suspend fun show(category: Category, sourceFilter: String?, limit: Int) {
        append("== ${category.name} source=${sourceFilter ?: "all"} ==")
        val started = System.currentTimeMillis()
        val items = runCatching { container.content.feed(category, 1, sourceFilter = sourceFilter) }
            .onFailure { append("feed failed: " + it.javaClass.simpleName + " " + it.message) }
            .getOrDefault(emptyList())
        append("items=" + items.size + " elapsed=" + (System.currentTimeMillis() - started) + "ms")
        val bySource = items.groupingBy { it.sourceId }.eachCount().entries
            .sortedByDescending { it.value }
            .joinToString(", ") { it.key + ":" + it.value }
        append("sources: " + bySource)
        if (category == Category.JAV) {
            val codes = items.mapNotNull { ContentFilters.productCode(it.title, it.vkey) }
            val counts = codes.groupingBy { it }.eachCount()
            val duplicates = counts.filter { it.value > 1 }
            append(
                "codes=" + codes.size + " unique=" + counts.size +
                    " duplicateCodes=" + duplicates.size +
                    " duplicateItems=" + duplicates.values.sum()
            )
            duplicates.entries.sortedByDescending { it.value }.take(8).forEach { entry ->
                append("  code " + entry.key + " x" + entry.value)
            }
        }
        for (video in items.take(limit)) {
            val bad = if (category == Category.JAV) {
                ContentFilters.isLowQuality(video.title, video.vkey)
            } else {
                ContentFilters.isCjkTitle(video.title)
            }
            append(
                "[" + video.sourceId + "] " + (if (bad) "BAD " else "") +
                    video.title.take(70)
            )
            Log.i("LocalFeedTest", category.name + "|" + video.sourceId + "|" + video.title)
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
