package com.simplecalculator.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Returns the translated title once it arrives, or null while it is unknown or
 * when translation is off. Cards render the original immediately, so nothing
 * waits on the network.
 */
@Composable
fun translatedTitle(original: String, enabled: Boolean): String? {
    val container = LocalContainer.current
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("calc_state", android.content.Context.MODE_PRIVATE)
    }
    val allowed = remember(prefs) { prefs.getBoolean("translate_titles", true) }
    var translated by remember(original) { mutableStateOf(container.translator.cached(original)) }

    LaunchedEffect(original, enabled) {
        if (!enabled || translated != null) return@LaunchedEffect
        if (!allowed) return@LaunchedEffect
        if (!container.translator.needsTranslation(original)) return@LaunchedEffect
        // Give artwork and the tapped title priority; a card scrolled away
        // before this delay never spends a translation request.
        kotlinx.coroutines.delay(300L)
        translated = container.translator.translate(original)
    }
    return translated
}
