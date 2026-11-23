package com.simplecalculator.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.screens.HomeScreen
import com.simplecalculator.app.ui.theme.CalculatorTheme

/**
 * Debug-only feed UI without the vault lock, for checking real cards and
 * images on a device.
 *
 * adb shell am start -n com.simplecalculator.app/.DebugHomeActivity
 */
class DebugHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as App).container
        setContent {
            CalculatorTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    HomeScreen(onOpen = {})
                }
            }
        }
    }
}
