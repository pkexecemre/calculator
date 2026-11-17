package com.simplecalculator.app.core

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Process-wide unlock state. The calculator decoy is always the entry screen;
 * this flag only decides whether the vault exists behind it.
 */
object Session {
    private val _unlocked = mutableStateOf(false)

    /** Exposed as State so composables can observe a lock from anywhere. */
    val unlockedState: State<Boolean> get() = _unlocked

    var unlocked: Boolean
        get() = _unlocked.value
        set(value) { _unlocked.value = value }

    var lastBackgroundAt: Long = 0L

    fun lock() {
        unlocked = false
    }

    fun unlock() {
        unlocked = true
        lastBackgroundAt = 0L
    }

    fun noteBackground() {
        lastBackgroundAt = System.currentTimeMillis()
    }

    fun shouldRelock(): Boolean {
        if (!unlocked) return false
        if (lastBackgroundAt == 0L) return false
        return System.currentTimeMillis() - lastBackgroundAt > RELOCK_AFTER_MS
    }

    private const val RELOCK_AFTER_MS = 90_000L
}
