package com.vzor.ai.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vzor.ai.ui.theme.VzorTheme
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// State holders (no ViewModel needed)
// ---------------------------------------------------------------------------

private data class LogsState(
    val latencyValues: List<Long> = emptyList(),
    val cacheHitRate: Float = 0f,
    val noiseProfile: String = "UNKNOWN",
    val voiceOrchestratorState: String = "IDLE",
    val routingDecisions: List<String> = emptyList(),
    val lastUpdated: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateBack: () -> Unit
) {
    // Simple state holder — auto-refreshes every 2 seconds
    var state by remember { mutableStateOf(LogsState()) }
    val routingLog = remember { mutableStateListOf<String>() }
    var refreshCounter by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000L)
            refreshCounter++
            // In production, these values would come from injected telemetry services.
            // For now, we display the placeholder state that can be wired up later.
            state = state.copy(lastUpdated = System.currentTimeMillis())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Developer Logs",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LogTimestamp(state.lastUpdated)

            LogSection(title = "AI Request Latency (last 10)") {
                if (state.latencyValues.isEmpty()) {
                    LogLine("No requests recorded yet")
                } else {
                    state.latencyValues.forEachIndexed { i, ms ->
                        LogLine("  [$i] ${ms}ms")
                    }
                    val avg = state.latencyValues.average().toLong()
                    LogLine("  avg: ${avg}ms | min: ${state.latencyValues.min()}ms | max: ${state.latencyValues.max()}ms")
                }
            }

            LogSection(title = "Cache") {
                LogLine("Hit rate: ${"%.1f".format(state.cacheHitRate * 100)}%")
            }

            LogSection(title = "Noise Profile") {
                LogLine("Current: ${state.noiseProfile}")
            }

            LogSection(title = "Voice Orchestrator") {
                LogLine("State: ${state.voiceOrchestratorState}")
            }

            LogSection(title = "Routing Decisions") {
                if (state.routingDecisions.isEmpty() && routingLog.isEmpty()) {
                    LogLine("No routing decisions recorded")
                } else {
                    val allDecisions = state.routingDecisions + routingLog
                    allDecisions.takeLast(20).forEach { entry ->
                        LogLine(entry)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Auto-refreshing every 2s | Counter: $refreshCounter",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun LogTimestamp(timestamp: Long) {
    val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    Text(
        text = "Last refresh: ${dateFormat.format(Date(timestamp))}",
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    )
}

@Composable
private fun LogSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            content()
        }
    }
}

@Composable
private fun LogLine(text: String, color: Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 18.sp
        ),
        color = color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    )
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenPreview() {
    VzorTheme {
        LogsScreen(onNavigateBack = {})
    }
}
