package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.simplecalculator.app.R
import com.simplecalculator.app.data.stt.ModelCatalog
import com.simplecalculator.app.data.stt.SttModel
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.theme.Surface2
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch

/**
 * First-run step: pick the speech model that will caption videos on-device.
 * The base model is already inside the APK, so "Start" works without a network.
 */
@Composable
fun SttOnboardingScreen(onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(container.modelStore.selected()) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var readyTick by remember { mutableStateOf(0) }

    // The base model lives in the APK; unpack it so "Continue" works offline.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        container.modelStore.ensureBundled()
        readyTick++
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.stt_onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.stt_onboarding_body),
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted
        )
        Spacer(Modifier.height(20.dp))

        ModelCatalog.all.forEach { model ->
            ModelRow(
                model = model,
                selected = model.id == selected.id,
                ready = readyTick >= 0 && container.modelStore.isReady(model),
                progress = if (model.id == selected.id) progress else null,
                onClick = {
                    selected = model
                    container.modelStore.select(model)
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        if (message != null) {
            Text(
                text = message ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(16.dp))

        val ready = container.modelStore.isReady(selected)
        if (!ready) {
            Button(
                onClick = {
                    scope.launch {
                        progress = 0
                        val ok = container.modelStore.download(selected) { progress = it }
                        progress = null
                        message = if (ok) null else container.appContext.getString(R.string.stt_download_failed)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    if (progress != null) {
                        stringResource(R.string.stt_downloading, progress ?: 0)
                    } else {
                        stringResource(R.string.stt_download_model, selected.sizeMb)
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { (progress ?: 0) / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
        } else {
            Button(
                onClick = {
                    container.appContext
                        .getSharedPreferences("stt", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("onboarded", true).apply()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(stringResource(R.string.stt_continue)) }
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = {
            container.appContext
                .getSharedPreferences("stt", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("onboarded", true).apply()
            onDone()
        }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stt_skip))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ModelRow(
    model: SttModel,
    selected: Boolean,
    ready: Boolean,
    progress: Int?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Surface2
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(model.note, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        Text(
            text = if (ready) stringResource(R.string.stt_ready)
            else "${model.sizeMb} MB",
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) MaterialTheme.colorScheme.secondary else TextMuted,
            textAlign = TextAlign.End
        )
    }
}
