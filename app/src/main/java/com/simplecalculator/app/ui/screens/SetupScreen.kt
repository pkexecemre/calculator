package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.theme.TextMuted

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val container = LocalContainer.current
    var step by remember { mutableStateOf(0) }
    var firstCode by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text(
            text = if (step == 0) stringResource(R.string.setup_title) else stringResource(R.string.setup_title_confirm),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (step == 0) stringResource(R.string.setup_body) else stringResource(R.string.setup_body_confirm),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        CodeDots(count = 6, filled = code.length, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Text(
            text = error ?: stringResource(R.string.setup_range),
            style = MaterialTheme.typography.labelMedium,
            color = if (error != null) MaterialTheme.colorScheme.error else TextMuted
        )
        Spacer(Modifier.weight(1f))
        PinPad(
            onDigit = { digit ->
                if (code.length < 6) {
                    code = code + digit
                    error = null
                }
            },
            onBackspace = {
                if (code.isNotEmpty()) code = code.dropLast(1)
                error = null
            }
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (code.length < 4) return@Button
                if (step == 0) {
                    firstCode = code
                    code = ""
                    step = 1
                } else if (code == firstCode) {
                    container.pinStore.setPin(code)
                    onDone()
                } else {
                    error = null
                    code = ""
                    step = 0
                    firstCode = ""
                }
            },
            enabled = code.length in 4..6,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(stringResource(R.string.setup_continue))
        }
        Spacer(Modifier.height(8.dp))
    }
}
