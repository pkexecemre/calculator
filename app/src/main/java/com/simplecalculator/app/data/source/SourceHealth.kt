package com.simplecalculator.app.data.source

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Tiny key/value surface so the health logic is testable without Android. */
interface HealthStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getLong(key: String, default: Long): Long
    fun putLong(key: String, value: Long)
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(vararg keys: String)
}

class SharedPreferencesHealthStore(context: Context) : HealthStore {
    private val prefs = context.getSharedPreferences("source_health", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    override fun getLong(key: String, default: Long) = prefs.getLong(key, default)
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    override fun getString(key: String) = prefs.getString(key, null)
    override fun putString(key: String, value: String?) { prefs.edit().putString(key, value).apply() }
    override fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }
}

class InMemoryHealthStore : HealthStore {
    private val values = ConcurrentHashMap<String, Any>()
    override fun getInt(key: String, default: Int) = (values[key] as? Int) ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getLong(key: String, default: Long) = (values[key] as? Long) ?: default
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun getString(key: String) = values[key] as? String
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun remove(vararg keys: String) = keys.forEach { values.remove(it) }
}

data class SourceHealthInfo(
    val id: String,
    val displayName: String,
    val category: Category,
    val failures: Int,
    val lastSuccessAt: Long,
    val lastError: String?,
    val pausedUntil: Long
) {
    val isPaused: Boolean get() = pausedUntil > System.currentTimeMillis()
}

/** Tracks per-source reliability and pauses sources that keep failing. */
class SourceHealth(
    private val store: HealthStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun recordSuccess(id: String) {
        store.putInt(key("fails", id), 0)
        store.putLong(key("ok", id), clock())
        store.putLong(key("paused", id), 0L)
        store.putString(key("error", id), null)
    }

    fun recordFailure(id: String, message: String?) {
        val fails = store.getInt(key("fails", id), 0) + 1
        store.putInt(key("fails", id), fails)
        store.putString(key("error", id), message?.take(160))
        if (fails >= FAIL_LIMIT) {
            store.putLong(key("paused", id), clock() + COOLDOWN_MS)
        }
    }

    fun isPaused(id: String): Boolean = store.getLong(key("paused", id), 0L) > clock()

    fun info(source: Source): SourceHealthInfo = SourceHealthInfo(
        id = source.id,
        displayName = source.displayName,
        category = source.category,
        failures = store.getInt(key("fails", source.id), 0),
        lastSuccessAt = store.getLong(key("ok", source.id), 0L),
        lastError = store.getString(key("error", source.id)),
        pausedUntil = store.getLong(key("paused", source.id), 0L)
    )

    fun snapshot(sources: List<Source>): List<SourceHealthInfo> = sources.map(::info)

    fun reset(id: String) = store.remove(
        key("fails", id), key("ok", id), key("error", id), key("paused", id)
    )

    fun resetAll(sources: List<Source>) = sources.forEach { reset(it.id) }

    private fun key(kind: String, id: String) = "health:$kind:$id"

    companion object {
        const val FAIL_LIMIT = 3
        const val COOLDOWN_MS = 30L * 60 * 1000
    }
}
