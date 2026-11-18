package com.simplecalculator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.simplecalculator.app.data.source.Category
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.screens.HomeScreen
import com.simplecalculator.app.ui.theme.CalculatorTheme

/**
 * Debug-only feed UI without the vault lock, for checking real cards, images
 * and the new content policy on a device.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugHomeActivity --es category JAV
 */
class DebugHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        val initial = intent.getStringExtra("category")
            ?.let { value -> runCatching { Category.valueOf(value.uppercase()) }.getOrNull() }
            ?: Category.JAV
        setContent {
            CalculatorTheme {
                var category by remember { mutableStateOf(initial) }
                CompositionLocalProvider(LocalContainer provides container) {
                    HomeScreen(
                        category = category,
                        onCategoryChange = { category = it },
                        onOpen = {},
                        onOpenSeries = {}
                    )
                }
            }
        }
    }
}
