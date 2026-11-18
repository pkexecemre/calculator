package com.simplecalculator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.screens.SearchScreen
import com.simplecalculator.app.ui.theme.CalculatorTheme

/** Debug-only search screen without the vault lock. */
class DebugSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        val category = intent.getStringExtra("category")
            ?.let { runCatching { Category.valueOf(it.uppercase()) }.getOrNull() }
            ?: Category.JAV
        setContent {
            CalculatorTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    SearchScreen(category = category, onOpen = {}, onOpenSeries = {})
                }
            }
        }
    }
}
