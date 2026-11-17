package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.simplecalculator.app.R
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.theme.CalculatorDisplayStyle

/**
 * The launcher-surface screen. It is a working calculator; the code typed as a
 * plain number followed by "=" opens the vault. Wrong codes simply compute.
 */
@Composable
fun CalculatorScreen(onUnlock: () -> Unit) {
    val container = LocalContainer.current
    val pinStore = container.pinStore
    val context = LocalContext.current
    val biometricAvailable = remember {
        BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticateBiometric() {
        val activity = context as? FragmentActivity ?: return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlock()
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.unlock_biometric_title))
                .setNegativeButtonText(context.getString(R.string.unlock_biometric_cancel))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
        )
    }

    var expression by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    fun push(character: Char) {
        if (notice != null) return
        expression = expression + character
        if (expression.length > 24) expression = expression.takeLast(24)
    }

    fun commit() {
        if (notice != null) return
        if (expression.isEmpty()) return
        val digitsOnly = expression.all { it.isDigit() }
        // Verify first: the right code should still work during a lockout.
        // A wrong one increments the counter and refreshes the cooldown.
        if (digitsOnly && expression.length == pinStore.pinLength && pinStore.verify(expression)) {
            expression = ""
            result = ""
            onUnlock()
            return
        }
        val cooldown = pinStore.cooldownRemainingMs()
        if (digitsOnly && expression.length == pinStore.pinLength && cooldown > 0L) {
            val seconds = (cooldown / 1000L) + 1L
            notice = context.getString(R.string.lockout_notice, seconds)
            expression = ""
            result = ""
            return
        }
        result = CalcEngine.evaluate(expression)
        expression = result
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End
        ) {
            if (notice != null) {
                Text(
                    text = notice ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.End
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = if (expression.isEmpty()) "0" else expression,
                style = CalculatorDisplayStyle,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (biometricAvailable) {
                Spacer(Modifier.height(8.dp))
                IconButton(onClick = { authenticateBiometric() }) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = stringResource(R.string.unlock_biometric),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        Keypad(
            onDigit = { digit -> push(digit) },
            onOperator = { op ->
                when (op) {
                    '.' -> if (!expression.endsWith(".")) push('.')
                    '-' -> push('-')
                    else -> push(op)
                }
            },
            onClear = {
                expression = ""
                result = ""
                notice = null
            },
            onBackspace = {
                if (notice != null) {
                    notice = null
                } else if (expression.isNotEmpty()) {
                    expression = expression.dropLast(1)
                }
            },
            onEquals = { commit() }
        )
        Spacer(Modifier.height(6.dp))
    }
}
