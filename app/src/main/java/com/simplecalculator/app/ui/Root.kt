package com.simplecalculator.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.simplecalculator.app.core.Session
import com.simplecalculator.app.ui.screens.CalculatorScreen
import com.simplecalculator.app.ui.screens.SetupScreen
import com.simplecalculator.app.ui.theme.CalculatorTheme
import com.simplecalculator.app.ui.theme.VaultTheme

@Composable
fun Root() {
    val container = LocalContainer.current
    var hasPin by remember { mutableStateOf(container.pinStore.hasPin) }
    // Observe the process-wide session directly so a lock triggered from
    // Settings or on background immediately swaps back to the calculator.
    val unlocked by Session.unlockedState

    when {
        !hasPin -> CalculatorTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SetupScreen(
                    onDone = { hasPin = true }
                )
            }
        }

        !unlocked -> CalculatorTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                CalculatorScreen(onUnlock = { Session.unlock() })
            }
        }

        else -> VaultTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                VaultApp()
            }
        }
    }
}
