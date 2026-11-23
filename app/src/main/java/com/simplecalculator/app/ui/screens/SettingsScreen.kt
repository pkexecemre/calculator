package com.simplecalculator.app.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.simplecalculator.app.BuildConfig
import com.simplecalculator.app.R
import com.simplecalculator.app.core.Session
import com.simplecalculator.app.ui.CategoryTabs
import com.simplecalculator.app.ui.LocalContainer
import com.simplecalculator.app.ui.theme.TextMuted
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("calc_state", android.content.Context.MODE_PRIVATE)
    }
    var secureScreen by remember { mutableStateOf(prefs.getBoolean("secure_screen", false)) }
    var lockOnBackground by remember { mutableStateOf(prefs.getBoolean("lock_on_background", true)) }
    var translateTitles by remember { mutableStateOf(prefs.getBoolean("translate_titles", true)) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val currentLocale = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { Locale.getDefault().language }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        CategoryTabs(
            labels = listOf("English", "Türkçe"),
            selectedIndex = if (currentLocale.startsWith("tr")) 1 else 0,
            onSelect = { index ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(if (index == 0) "en" else "tr")
                )
            }
        )

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_secure), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.settings_secure_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            androidx.compose.material3.Switch(
                checked = secureScreen,
                onCheckedChange = { checked ->
                    secureScreen = checked
                    prefs.edit().putBoolean("secure_screen", checked).apply()
                    (context as? com.simplecalculator.app.MainActivity)?.applySecureFlag()
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_translate), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.settings_translate_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            androidx.compose.material3.Switch(
                checked = translateTitles,
                onCheckedChange = { checked ->
                    translateTitles = checked
                    prefs.edit().putBoolean("translate_titles", checked).apply()
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_lock_bg), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.settings_lock_bg_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            androidx.compose.material3.Switch(
                checked = lockOnBackground,
                onCheckedChange = { checked ->
                    lockOnBackground = checked
                    prefs.edit().putBoolean("lock_on_background", checked).apply()
                }
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_lock_now),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clickable { Session.lock() }
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.settings_change_code),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clickable { showCodeDialog = true }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_change_code_body),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.settings_clear_history),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().clickable {
                scope.launch {
                    container.content.clearHistory()
                    message = context.getString(R.string.settings_msg_history)
                }
            }
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_clear_history_body),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )

        if (container.hidden.count() > 0) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.settings_hidden, container.hidden.count()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().clickable {
                    container.hidden.clear()
                    message = context.getString(R.string.settings_hidden_cleared)
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_hidden_body),
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        val sttModel = container.modelStore.selected()
        var sttProgress by remember { mutableStateOf<Int?>(null) }
        Text(stringResource(R.string.settings_stt), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                R.string.settings_stt_body,
                sttModel.label,
                sttModel.sizeMb
            ),
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            com.simplecalculator.app.data.stt.ModelCatalog.all.forEach { model ->
                val active = model.id == sttModel.id
                val ready = container.modelStore.isReady(model)
                OutlinedButton(
                    onClick = {
                        container.modelStore.select(model)
                        if (!ready) {
                            scope.launch {
                                sttProgress = 0
                                container.modelStore.download(model) { sttProgress = it }
                                sttProgress = null
                                message = context.getString(R.string.settings_stt_done)
                            }
                        }
                    },
                    enabled = !active || !ready
                ) {
                    Text(
                        text = if (active && ready) "✓ ${model.shortLabel}" else model.shortLabel,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
        if (sttProgress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (sttProgress ?: 0) / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
        }

        Spacer(Modifier.height(28.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.settings_about), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.settings_about_body) + "\n" +
                "v" + BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted
        )
    }

    if (showCodeDialog) {
        AlertDialog(
            onDismissRequest = { showCodeDialog = false; code = "" },
            title = { Text(stringResource(R.string.settings_change_code)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CodeDots(count = 6, filled = code.length, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    PinPad(
                        onDigit = { if (code.length < 6) code = code + it },
                        onBackspace = { if (code.isNotEmpty()) code = code.dropLast(1) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (code.length in 4..6) {
                            container.pinStore.setPin(code)
                            code = ""
                            showCodeDialog = false
                            message = context.getString(R.string.settings_msg_code)
                        }
                    },
                    enabled = code.length in 4..6
                ) { Text(stringResource(R.string.setup_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showCodeDialog = false; code = "" }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
