package com.vzor.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery0Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BatteryUnknown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vzor.ai.ui.theme.VzorTheme

private val BatteryGreen = Color(0xFF4CAF50)
private val BatteryYellow = Color(0xFFFFC107)
private val BatteryRed = Color(0xFFF44336)

@Composable
fun GlassesBatteryIndicator(
    level: Int?,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val (icon, color) = when {
        !isConnected || level == null -> Icons.Default.BatteryUnknown to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        level > 75 -> Icons.Default.BatteryFull to BatteryGreen
        level > 25 -> Icons.Default.Battery3Bar to BatteryYellow
        else -> Icons.Default.Battery0Bar to BatteryRed
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = "Battery level",
            modifier = Modifier.size(20.dp),
            tint = color
        )
        Text(
            text = if (isConnected && level != null) "$level%" else "--",
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun GlassesBatteryIndicatorPreview() {
    VzorTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassesBatteryIndicator(level = 90, isConnected = true)
            GlassesBatteryIndicator(level = 50, isConnected = true)
            GlassesBatteryIndicator(level = 15, isConnected = true)
            GlassesBatteryIndicator(level = null, isConnected = false)
        }
    }
}
