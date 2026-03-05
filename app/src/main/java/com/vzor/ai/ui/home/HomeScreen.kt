package com.vzor.ai.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.domain.model.GlassesState
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.glasses.GlassesManager
import com.vzor.ai.orchestrator.VoiceOrchestrator
import com.vzor.ai.ui.components.GlassesBatteryIndicator
import com.vzor.ai.ui.components.RoutingBadge
import com.vzor.ai.ui.components.VoiceStateIndicator
import com.vzor.ai.ui.theme.VzorTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// UI State
// ---------------------------------------------------------------------------

data class HomeUiState(
    val glassesState: GlassesState = GlassesState.DISCONNECTED,
    val voiceState: VoiceState = VoiceState.IDLE,
    val routingMode: String = "CLOUD",
    val batteryLevel: Int? = null
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val glassesManager: GlassesManager,
    private val voiceOrchestrator: VoiceOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            glassesManager.state.collect { state ->
                _uiState.update {
                    it.copy(
                        glassesState = state,
                        // Clear battery when disconnected
                        batteryLevel = if (state == GlassesState.DISCONNECTED) null else it.batteryLevel
                    )
                }
            }
        }

        viewModelScope.launch {
            voiceOrchestrator.state.collect { state ->
                _uiState.update { it.copy(voiceState = state) }
            }
        }
    }

    fun onStartListening() {
        viewModelScope.launch {
            glassesManager.startAudioCapture()
        }
    }

    fun onTakePhoto() {
        viewModelScope.launch {
            glassesManager.capturePhoto()
        }
    }

    fun onConnectGlasses() {
        viewModelScope.launch {
            glassesManager.connect()
        }
    }

    fun onDisconnectGlasses() {
        viewModelScope.launch {
            glassesManager.disconnect()
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    HomeScreenContent(
        uiState = uiState,
        onNavigateToSettings = onNavigateToSettings,
        onStartListening = viewModel::onStartListening,
        onTakePhoto = viewModel::onTakePhoto,
        onConnectGlasses = viewModel::onConnectGlasses,
        onDisconnectGlasses = viewModel::onDisconnectGlasses
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    onNavigateToSettings: () -> Unit,
    onStartListening: () -> Unit,
    onTakePhoto: () -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Vzor AI",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // -- Glasses Connection Status --
            GlassesStatusCard(
                glassesState = uiState.glassesState,
                batteryLevel = uiState.batteryLevel,
                onConnect = onConnectGlasses,
                onDisconnect = onDisconnectGlasses
            )

            // -- Status Row: Routing + Voice --
            StatusRow(
                routingMode = uiState.routingMode,
                voiceState = uiState.voiceState
            )

            // -- Quick Actions --
            QuickActionsCard(
                isGlassesConnected = uiState.glassesState == GlassesState.CONNECTED ||
                        uiState.glassesState == GlassesState.STREAMING_AUDIO,
                onStartListening = onStartListening,
                onTakePhoto = onTakePhoto,
                onSettings = onNavigateToSettings
            )
        }
    }
}

@Composable
private fun GlassesStatusCard(
    glassesState: GlassesState,
    batteryLevel: Int?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isConnected = glassesState == GlassesState.CONNECTED ||
            glassesState == GlassesState.STREAMING_AUDIO ||
            glassesState == GlassesState.CAPTURING_PHOTO

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = if (isConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Column {
                        Text(
                            text = "Meta Ray-Ban Glasses",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isConnected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(
                            text = glassesStateLabel(glassesState),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isConnected) {
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                    }
                }

                GlassesBatteryIndicator(
                    level = batteryLevel,
                    isConnected = isConnected
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = if (isConnected) onDisconnect else onConnect,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (glassesState) {
                        GlassesState.DISCONNECTED -> "Connect"
                        GlassesState.CONNECTING -> "Connecting..."
                        GlassesState.ERROR -> "Retry Connection"
                        else -> "Disconnect"
                    }
                )
            }
        }
    }
}

@Composable
private fun StatusRow(
    routingMode: String,
    voiceState: VoiceState
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "AI Routing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                RoutingBadge(routingMode = routingMode)
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Voice State",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = voiceStateLabel(voiceState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    VoiceStateIndicator(state = voiceState)
                }
            }
        }
    }
}

@Composable
private fun QuickActionsCard(
    isGlassesConnected: Boolean,
    onStartListening: () -> Unit,
    onTakePhoto: () -> Unit,
    onSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.Mic,
                    label = "Start Listening",
                    onClick = onStartListening,
                    enabled = isGlassesConnected,
                    modifier = Modifier.weight(1f)
                )

                QuickActionButton(
                    icon = Icons.Default.CameraAlt,
                    label = "Take Photo",
                    onClick = onTakePhoto,
                    enabled = isGlassesConnected,
                    modifier = Modifier.weight(1f)
                )

                QuickActionButton(
                    icon = Icons.Default.Settings,
                    label = "Settings",
                    onClick = onSettings,
                    enabled = true,
                    modifier = Modifier.weight(1f)
                )
            }

            AnimatedVisibility(visible = !isGlassesConnected) {
                Text(
                    text = "Connect glasses to enable listening and camera",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(80.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun glassesStateLabel(state: GlassesState): String = when (state) {
    GlassesState.DISCONNECTED -> "Disconnected"
    GlassesState.CONNECTING -> "Connecting..."
    GlassesState.CONNECTED -> "Connected"
    GlassesState.STREAMING_AUDIO -> "Streaming Audio"
    GlassesState.CAPTURING_PHOTO -> "Capturing Photo"
    GlassesState.ERROR -> "Connection Error"
}

private fun voiceStateLabel(state: VoiceState): String = when (state) {
    VoiceState.IDLE -> "Idle"
    VoiceState.LISTENING -> "Listening"
    VoiceState.PROCESSING -> "Processing"
    VoiceState.GENERATING -> "Generating"
    VoiceState.RESPONDING -> "Responding"
    VoiceState.CONFIRMING -> "Confirming"
    VoiceState.SUSPENDED -> "Suspended"
    VoiceState.ERROR -> "Error"
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    VzorTheme {
        HomeScreenContent(
            uiState = HomeUiState(
                glassesState = GlassesState.CONNECTED,
                voiceState = VoiceState.IDLE,
                routingMode = "CLOUD",
                batteryLevel = 72
            ),
            onNavigateToSettings = {},
            onStartListening = {},
            onTakePhoto = {},
            onConnectGlasses = {},
            onDisconnectGlasses = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenDisconnectedPreview() {
    VzorTheme {
        HomeScreenContent(
            uiState = HomeUiState(),
            onNavigateToSettings = {},
            onStartListening = {},
            onTakePhoto = {},
            onConnectGlasses = {},
            onDisconnectGlasses = {}
        )
    }
}
