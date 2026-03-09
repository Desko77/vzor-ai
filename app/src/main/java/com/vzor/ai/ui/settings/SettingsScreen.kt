package com.vzor.ai.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vzor.ai.R
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.SttProvider
import com.vzor.ai.domain.model.TtsProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
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
            // AI Provider selection
            SectionTitle(stringResource(R.string.ai_provider))
            ProviderSelector(
                selected = uiState.aiProvider,
                onSelect = viewModel::setAiProvider
            )

            // API Keys
            SectionTitle(stringResource(R.string.api_keys))

            ApiKeyField(
                label = "Gemini API Key",
                value = uiState.geminiApiKey,
                onValueChange = viewModel::setGeminiApiKey
            )

            ApiKeyField(
                label = "Claude API Key",
                value = uiState.claudeApiKey,
                onValueChange = viewModel::setClaudeApiKey
            )

            ApiKeyField(
                label = "OpenAI API Key",
                value = uiState.openAiApiKey,
                onValueChange = viewModel::setOpenAiApiKey
            )

            ApiKeyField(
                label = "GLM-5 (Zhipu) API Key",
                value = uiState.glmApiKey,
                onValueChange = viewModel::setGlmApiKey
            )

            ApiKeyField(
                label = "Tavily Search API Key",
                value = uiState.tavilyApiKey,
                onValueChange = viewModel::setTavilyApiKey
            )

            ApiKeyField(
                label = "Picovoice Access Key (wake word)",
                value = uiState.picovoiceAccessKey,
                onValueChange = viewModel::setPicovoiceAccessKey
            )

            HorizontalDivider()

            // Local AI
            SectionTitle("Local AI")

            OutlinedTextField(
                value = uiState.localAiHost,
                onValueChange = viewModel::setLocalAiHost,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Local AI Host (IP:port)") },
                singleLine = true,
                placeholder = { Text("192.168.1.100") }
            )

            OutlinedTextField(
                value = uiState.homeSsid,
                onValueChange = viewModel::setHomeSsid,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Домашняя Wi-Fi (SSID)") },
                singleLine = true,
                supportingText = { Text("Автопереключение на Local AI при подключении") },
                placeholder = { Text("MyHomeNetwork") }
            )

            HorizontalDivider()

            // STT Provider
            SectionTitle(stringResource(R.string.stt_provider))
            SttProviderSelector(
                selected = uiState.sttProvider,
                onSelect = viewModel::setSttProvider
            )

            // TTS Provider
            SectionTitle(stringResource(R.string.tts_provider))
            TtsProviderSelector(
                selected = uiState.ttsProvider,
                onSelect = viewModel::setTtsProvider
            )

            if (uiState.ttsProvider == TtsProvider.YANDEX) {
                ApiKeyField(
                    label = "Yandex API Key",
                    value = uiState.yandexApiKey,
                    onValueChange = viewModel::setYandexApiKey
                )
            }

            HorizontalDivider()

            // System Prompt
            SectionTitle(stringResource(R.string.system_prompt))
            OutlinedTextField(
                value = uiState.systemPrompt,
                onValueChange = viewModel::setSystemPrompt,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                label = { Text(stringResource(R.string.system_prompt_hint)) }
            )

            HorizontalDivider()

            // Режим разработчика
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Режим разработчика",
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(
                    checked = uiState.developerMode,
                    onCheckedChange = viewModel::setDeveloperMode
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ProviderSelector(
    selected: AiProvider,
    onSelect: (AiProvider) -> Unit
) {
    Column {
        AiProvider.CLOUD_PROVIDERS.forEach { provider ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = provider == selected,
                    onClick = { onSelect(provider) }
                )
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SttProviderSelector(
    selected: SttProvider,
    onSelect: (SttProvider) -> Unit
) {
    Column {
        SttProvider.entries.forEach { provider ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = provider == selected,
                    onClick = { onSelect(provider) }
                )
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun TtsProviderSelector(
    selected: TtsProvider,
    onSelect: (TtsProvider) -> Unit
) {
    Column {
        TtsProvider.entries.forEach { provider ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RadioButton(
                    selected = provider == selected,
                    onClick = { onSelect(provider) }
                )
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Text(
                    if (visible) "Скрыть" else "Показать",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    )
}
