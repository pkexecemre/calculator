package com.simplecalculator.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable

@Composable
fun Keypad(
    onDigit: (Char) -> Unit,
    onOperator: (Char) -> Unit,
    onClear: () -> Unit,
    onBackspace: () -> Unit,
    onEquals: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("C", "⌫", "%", "÷"),
        listOf("7", "8", "9", "×"),
        listOf("4", "5", "6", "−"),
        listOf("1", "2", "3", "+"),
        listOf("±", "0", ".", "=")
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (label in row) {
                    val accent = label in listOf("÷", "×", "−", "+", "=")
                    val muted = label in listOf("C", "⌫", "%", "±")
                    Key(
                        label = label,
                        accent = accent,
                        muted = muted,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (label) {
                                "C" -> onClear()
                                "⌫" -> onBackspace()
                                "=" -> onEquals()
                                "%" -> onOperator('%')
                                "÷" -> onOperator('÷')
                                "×" -> onOperator('×')
                                "+" -> onOperator('+')
                                "−" -> onOperator('-')
                                "±" -> onOperator('-')
                                "." -> onOperator('.')
                                else -> onDigit(label[0])
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Key(
    label: String,
    accent: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        accent -> scheme.primary
        muted -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val content = when {
        accent -> scheme.onPrimary
        muted -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Surface(
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(26.dp),
        color = background,
        onClick = onClick
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = if (label.length > 1) 18.sp else 24.sp,
                fontWeight = FontWeight.Medium,
                color = content
            )
        }
    }
}

@Composable
fun CodeDots(count: Int, filled: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (index in 0 until count) {
            Surface(
                shape = CircleShape,
                color = if (index < filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(13.dp)
            ) {}
        }
    }
}

@Composable
fun PinPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (label in row) {
                    if (label.isEmpty()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        continue
                    }
                    Surface(
                        modifier = Modifier.weight(1f).height(68.dp),
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface,
                        onClick = {
                            if (label == "⌫") onBackspace() else onDigit(label[0])
                        }
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 20.dp),
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = label,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (label == "⌫") Color(0xFF9A9AA8) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
