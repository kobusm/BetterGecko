package com.bettergecko.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bettergecko.app.data.GeckoDevice
import com.bettergecko.app.data.GeckoState

val GeckoGreen = Color(0xFF34C759)
val GeckoGreenDark = Color(0xFF0A5229)
val SolarOrange = Color(0xFFFF9500)
val AcRed = Color(0xFFFF3B30)
val IdleBlue = Color(0xFF007AFF)

@Composable
fun BetterGeckoTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = GeckoGreen, secondary = SolarOrange)
    else
        lightColorScheme(primary = Color(0xFF1E8E3E), secondary = SolarOrange)
    MaterialTheme(colorScheme = scheme, content = content)
}

fun tempColor(t: Double?, fallback: Color): Color = when {
    t == null -> fallback
    t >= 65 -> AcRed
    t <= 30 -> IdleBlue
    else -> SolarOrange
}

fun GeckoState.color(fallback: Color): Color = when (this) {
    GeckoState.SOLAR_HEATING -> SolarOrange
    GeckoState.ELEMENT_HEATING -> AcRed
    GeckoState.IDLE -> IdleBlue
    else -> fallback
}

val GeckoState.icon: ImageVector get() = when (this) {
    GeckoState.SOLAR_HEATING -> Icons.Filled.WbSunny
    GeckoState.ELEMENT_HEATING -> Icons.Filled.Bolt
    GeckoState.IDLE -> Icons.Filled.DarkMode
    GeckoState.OFF -> Icons.Filled.PowerSettingsNew
    GeckoState.UNKNOWN -> Icons.Filled.QuestionMark
}

/** Rounded tinted card — the iOS `.background(.quaternary, in: RoundedRectangle)` look. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}

@Composable
fun StatCard(title: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = color,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(16.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = GeckoGreen)) { Text(label) }
        }
    }
}

/** Segmented control. */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
            ) { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun DevicePicker(devices: List<GeckoDevice>, selected: GeckoDevice?, onSelect: (GeckoDevice) -> Unit, modifier: Modifier = Modifier) {
    if (devices.size > 1 && selected != null)
        Segmented(devices, selected, { it.displayName }, onSelect, modifier)
}
