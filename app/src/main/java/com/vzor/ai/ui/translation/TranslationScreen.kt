package com.vzor.ai.ui.translation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.translation.TranslationManager
import com.vzor.ai.translation.TranslationMode
import com.vzor.ai.translation.TranslationResult
import com.vzor.ai.translation.TranslationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

data class TranslationUiState(
    val selectedMode: TranslationMode = TranslationMode.LISTEN,
    val sourceLang: String = "RU",
    val targetLang: String = "EN",
    val isReversed: Boolean = false
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val translationManager: TranslationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    val translationState: StateFlow<TranslationState> = translationManager.translationState
    val lastTranslation: StateFlow<TranslationResult?> = translationManager.lastTranslation

    fun selectMode(mode: TranslationMode) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun swapLanguages() {
        _uiState.update {
            val newReversed = !it.isReversed
            it.copy(
                isReversed = newReversed,
                sourceLang = if (newReversed) "EN" else "RU",
                targetLang = if (newReversed) "RU" else "EN"
            )
        }
        updateManagerLanguages()
    }

    fun selectLanguagePair(source: String, target: String) {
        _uiState.update {
            it.copy(
                sourceLang = source,
                targetLang = target,
                isReversed = source == "EN"
            )
        }
        updateManagerLanguages()
    }

    private fun updateManagerLanguages() {
        val state = _uiState.value
        translationManager.setLanguages(
            source = state.sourceLang.lowercase(),
            target = state.targetLang.lowercase()
        )
    }

    fun startTranslation() {
        updateManagerLanguages()
        translationManager.startTranslation(_uiState.value.selectedMode)
    }

    fun stopTranslation() {
        translationManager.stopTranslation()
    }

    fun onPttPressed() {
        val currentState = translationState.value
        if (currentState.isActive) {
            stopTranslation()
        } else {
            startTranslation()
        }
    }

    override fun onCleared() {
        super.onCleared()
        translationManager.stopTranslation()
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
    onNavigateBack: () -> Unit,
    viewModel: TranslationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val translationState by viewModel.translationState.collectAsState()
    val lastTranslation by viewModel.lastTranslation.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "\u041F\u0435\u0440\u0435\u0432\u043E\u0434",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
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
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Language pair selector
            LanguagePairSelector(
                sourceLang = uiState.sourceLang,
                targetLang = uiState.targetLang,
                onSwap = viewModel::swapLanguages,
                onSelectPair = viewModel::selectLanguagePair
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Mode selector
            ModeSelector(
                selectedMode = uiState.selectedMode,
                onModeSelected = viewModel::selectMode
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Translation display area
            TranslationDisplayArea(
                lastTranslation = lastTranslation,
                isActive = translationState.isActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            // Status indicator
            StatusIndicator(
                status = translationState.status,
                isActive = translationState.isActive
            )

            Spacer(modifier = Modifier.height(16.dp))

            // PTT button
            PushToTalkButton(
                isActive = translationState.isActive,
                onPress = viewModel::onPttPressed
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Language Pair Selector
// ---------------------------------------------------------------------------

@Composable
private fun LanguagePairSelector(
    sourceLang: String,
    targetLang: String,
    onSwap: () -> Unit,
    onSelectPair: (String, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = sourceLang == "RU",
            onClick = { onSelectPair("RU", "EN") },
            label = {
                Text(
                    text = "RU \u2192 EN",
                    style = MaterialTheme.typography.labelLarge
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        Spacer(modifier = Modifier.width(8.dp))

        IconButton(onClick = onSwap) {
            Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = "Swap languages",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        FilterChip(
            selected = sourceLang == "EN",
            onClick = { onSelectPair("EN", "RU") },
            label = {
                Text(
                    text = "EN \u2192 RU",
                    style = MaterialTheme.typography.labelLarge
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Mode Selector
// ---------------------------------------------------------------------------

@Composable
private fun ModeSelector(
    selectedMode: TranslationMode,
    onModeSelected: (TranslationMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModeButton(
            icon = Icons.Default.Hearing,
            label = "\u0421\u043B\u0443\u0448\u0430\u0442\u044C",
            mode = TranslationMode.LISTEN,
            isSelected = selectedMode == TranslationMode.LISTEN,
            onClick = { onModeSelected(TranslationMode.LISTEN) },
            modifier = Modifier.weight(1f)
        )

        ModeButton(
            icon = Icons.Default.RecordVoiceOver,
            label = "\u0413\u043E\u0432\u043E\u0440\u0438\u0442\u044C",
            mode = TranslationMode.SPEAK,
            isSelected = selectedMode == TranslationMode.SPEAK,
            onClick = { onModeSelected(TranslationMode.SPEAK) },
            modifier = Modifier.weight(1f)
        )

        ModeButton(
            icon = Icons.Default.SwapHoriz,
            label = "\u041E\u0431\u0430",
            mode = TranslationMode.BIDIRECTIONAL,
            isSelected = selectedMode == TranslationMode.BIDIRECTIONAL,
            onClick = { onModeSelected(TranslationMode.BIDIRECTIONAL) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeButton(
    icon: ImageVector,
    label: String,
    mode: TranslationMode,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        label = "modeButtonColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "modeButtonContentColor"
    )

    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Translation Display Area
// ---------------------------------------------------------------------------

@Composable
private fun TranslationDisplayArea(
    lastTranslation: TranslationResult?,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (lastTranslation != null) {
                // Source text
                Text(
                    text = lastTranslation.sourceText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Language direction label
                Text(
                    text = "${lastTranslation.sourceLang.uppercase()} \u2192 ${lastTranslation.targetLang.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Translated text (large, bold)
                Text(
                    text = lastTranslation.translatedText,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Latency badge
                Text(
                    text = "${lastTranslation.latencyMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                // Empty state
                val placeholderAlpha = if (isActive) 0.7f else 0.4f
                Text(
                    text = if (isActive) {
                        "\u0421\u043B\u0443\u0448\u0430\u044E..."
                    } else {
                        "\u041D\u0430\u0436\u043C\u0438\u0442\u0435 \u043A\u043D\u043E\u043F\u043A\u0443\n\u0434\u043B\u044F \u043D\u0430\u0447\u0430\u043B\u0430 \u043F\u0435\u0440\u0435\u0432\u043E\u0434\u0430"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = placeholderAlpha),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Status Indicator
// ---------------------------------------------------------------------------

@Composable
private fun StatusIndicator(
    status: String,
    isActive: Boolean
) {
    if (!isActive) return

    val displayStatus = when {
        status.startsWith("listening") -> "\u0421\u043B\u0443\u0448\u0430\u044E..."
        status.startsWith("translating") -> "\u041F\u0435\u0440\u0435\u0432\u043E\u0436\u0443..."
        status.startsWith("speaking") -> "\u0413\u043E\u0432\u043E\u0440\u044E..."
        status.startsWith("error") -> "\u041E\u0448\u0438\u0431\u043A\u0430"
        else -> status
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            status.startsWith("listening") -> MaterialTheme.colorScheme.primary
            status.startsWith("translating") -> MaterialTheme.colorScheme.tertiary
            status.startsWith("speaking") -> MaterialTheme.colorScheme.secondary
            status.startsWith("error") -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(pulseAlpha)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = displayStatus,
            style = MaterialTheme.typography.bodyMedium,
            color = statusColor.copy(alpha = pulseAlpha)
        )
    }
}

// ---------------------------------------------------------------------------
// Push-to-Talk Button
// ---------------------------------------------------------------------------

@Composable
private fun PushToTalkButton(
    isActive: Boolean,
    onPress: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val buttonColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        label = "pttButtonColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isActive) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        },
        label = "pttBorderColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isActive) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pttPulseScale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .scale(pulseScale)
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .border(
                    width = 3.dp,
                    color = borderColor,
                    shape = CircleShape
                )
        )

        // Inner button
        Button(
            onClick = onPress,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonColor
            ),
            interactionSource = interactionSource
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = if (isActive) "Stop" else "Start translation",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
