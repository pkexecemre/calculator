package com.simplecalculator.app.core

import android.content.Context

/**
 * Titles and series the user dismissed from the feed. Stored as a small string
 * set so a junk card never comes back, without touching the source data.
 */
class HiddenStore(context: Context) {

    private val prefs = context.getSharedPreferences("hidden_titles", Context.MODE_PRIVATE)

    fun keys(): Set<String> = prefs.getStringSet(KEY_ITEMS, emptySet()).orEmpty()

    fun isHidden(key: String): Boolean = key in keys()

    fun hide(key: String, title: String) {
        val current = keys().toMutableSet()
        current.add(key)
        prefs.edit().putStringSet(KEY_ITEMS, current).apply()
        val titles = titles().toMutableSet()
        titles.add(title)
        prefs.edit().putStringSet(KEY_TITLES, titles).apply()
    }

    fun unhide(key: String) {
        val current = keys().toMutableSet()
        current.remove(key)
        prefs.edit().putStringSet(KEY_ITEMS, current).apply()
    }

    /** Titles are kept so a series card with a fresh key is filtered too. */
    fun titles(): Set<String> = prefs.getStringSet(KEY_TITLES, emptySet()).orEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun count(): Int = keys().size

    private companion object {
        const val KEY_ITEMS = "keys"
        const val KEY_TITLES = "titles"
    }
}
