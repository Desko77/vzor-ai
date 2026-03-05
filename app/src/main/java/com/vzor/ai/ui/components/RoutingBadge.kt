package com.vzor.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vzor.ai.ui.theme.VzorTheme

private val LocalGreen = Color(0xFF4CAF50)
private val CloudBlue = Color(0xFF2196F3)
private val OfflineOrange = Color(0xFFFF9800)

@Composable
fun RoutingBadge(
    routingMode: String,
    modifier: Modifier = Modifier
) {
    val (label, color, icon) = when (routingMode.uppercase()) {
        "LOCAL" -> Triple("Local AI", LocalGreen, Icons.Default.Memory)
        "CLOUD" -> Triple("Cloud", CloudBlue, Icons.Default.Cloud)
        "OFFLINE" -> Triple("Offline", OfflineOrange, Icons.Default.CloudOff)
        else -> Triple(routingMode, MaterialTheme.colorScheme.outline, Icons.Default.Cloud)
    }

    AssistChip(
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        modifier = modifier,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.12f),
            labelColor = color
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = color.copy(alpha = 0.3f)
        )
    )
}

@Preview(showBackground = true)
@Composable
private fun RoutingBadgePreview() {
    VzorTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutingBadge(routingMode = "LOCAL")
            RoutingBadge(routingMode = "CLOUD")
            RoutingBadge(routingMode = "OFFLINE")
        }
    }
}
