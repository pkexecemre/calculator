package com.simplecalculator.app.ui

import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.simplecalculator.app.data.source.VideoSummary

/**
 * Warms the Coil caches for cards that are about to scroll into view, so a slow
 * connection shows real artwork instead of grey boxes.
 */
object ImageWarmup {

    fun prefetch(context: Context, items: List<VideoSummary>, limit: Int = 12) {
        val loader = context.imageLoader
        items.asSequence()
            .map { normalizeImageUrl(it.thumb) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(limit)
            .forEach { url ->
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                loader.enqueue(request)
            }
    }
}
