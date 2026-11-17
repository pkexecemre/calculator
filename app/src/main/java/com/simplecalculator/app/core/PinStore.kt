package com.simplecalculator.app.core

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores the vault code as PBKDF2-SHA256(salt, code). The plaintext code never
 * touches disk, and the attempt counter is kept here so a wrong-code loop cannot
 * be used to brute force the four-to-six digit space from the lock screen.
 */
class PinStore(context: Context) {

    private val prefs = context.getSharedPreferences("calc_state", Context.MODE_PRIVATE)

    val hasPin: Boolean
        get() = prefs.contains(KEY_HASH)

    val pinLength: Int
        get() = prefs.getInt(KEY_LEN, 4)

    fun setPin(pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val derived = derive(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(derived, Base64.NO_WRAP))
            .putInt(KEY_LEN, pin.length)
            .putInt(KEY_FAILS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()
    }

    fun verify(pin: String): Boolean {
        val saltRaw = prefs.getString(KEY_SALT, null) ?: return false
        val hashRaw = prefs.getString(KEY_HASH, null) ?: return false
        val expected = Base64.decode(hashRaw, Base64.NO_WRAP)
        val actual = derive(pin, Base64.decode(saltRaw, Base64.NO_WRAP))
        val ok = MessageDigest.isEqual(expected, actual)
        if (ok) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            onFailure()
        }
        return ok
    }

    fun cooldownRemainingMs(): Long {
        val until = prefs.getLong(KEY_LOCK_UNTIL, 0L)
        val left = until - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }

    private fun onFailure() {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            // 5th wrong code: 30s, 10th: 60s, 15th: 120s ... capped at 15 min.
            // `fails` keeps climbing during a cooldown, so the penalty really grows.
            val level = ((fails - FREE_ATTEMPTS) / FREE_ATTEMPTS).coerceAtMost(5)
            val penalty = minOf(30_000L * (1 shl level), 15 * 60_000L)
            editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + penalty)
        }
        editor.apply()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    companion object {
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_LEN = "pin_len"
        private const val KEY_FAILS = "pin_fails"
        private const val KEY_LOCK_UNTIL = "pin_lock_until"
        private const val ITERATIONS = 120_000
        private const val FREE_ATTEMPTS = 5
    }
}
