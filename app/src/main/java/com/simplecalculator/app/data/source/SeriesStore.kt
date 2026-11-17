package com.simplecalculator.app.data.source

import java.util.concurrent.ConcurrentHashMap

/** In-memory hand-off from a grouped feed to the series detail route. */
object SeriesStore {
    private val groups = ConcurrentHashMap<String, SeriesGroup>()

    /**
     * Merges, never clears: a feed refresh must not drop the group a detail
     * screen is currently showing. Bounded so long sessions cannot grow forever.
     */
    fun putAll(items: List<SeriesGroup>): List<SeriesGroup> {
        items.forEach { groups[it.key] = it }
        if (groups.size > MAX_GROUPS) {
            groups.keys.take(groups.size - MAX_GROUPS).forEach { groups.remove(it) }
        }
        return items
    }

    private const val MAX_GROUPS = 400

    fun get(key: String): SeriesGroup? = groups[key]
}
