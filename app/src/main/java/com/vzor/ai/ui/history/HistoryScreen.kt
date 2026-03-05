package com.vzor.ai.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.data.local.SessionLogDao
import com.vzor.ai.ui.theme.VzorTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// ---------------------------------------------------------------------------
// Domain model for display
// ---------------------------------------------------------------------------

data class SessionLogItem(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long?,
    val messageCount: Int,
    val provider: String,
    val summary: String?
)

// ---------------------------------------------------------------------------
// UI State
// ---------------------------------------------------------------------------

data class HistoryUiState(
    val sessions: List<SessionLogItem> = emptyList(),
    val isLoading: Boolean = true
)

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionLogDao: SessionLogDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entities = sessionLogDao.getAll()
            val items = entities.map { entity ->
                SessionLogItem(
                    sessionId = entity.sessionId,
                    startedAt = entity.startedAt,
                    endedAt = entity.endedAt,
                    messageCount = entity.messageCount,
                    provider = entity.provider,
                    summary = entity.summary
                )
            }
            _uiState.update { it.copy(sessions = items, isLoading = false) }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(sessions = state.sessions.filter { it.sessionId != sessionId })
            }
            // Delete from DB by fetching the entity then using DAO
            val entity = sessionLogDao.getById(sessionId)
            if (entity != null) {
                sessionLogDao.deleteOlderThan(entity.startedAt + 1)
                // Reload to stay consistent
                loadSessions()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateToSettings: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    HistoryScreenContent(
        uiState = uiState,
        onNavigateToSettings = onNavigateToSettings,
        onDeleteSession = viewModel::deleteSession
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreenContent(
    uiState: HistoryUiState,
    onNavigateToSettings: () -> Unit,
    onDeleteSession: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "History",
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
        if (uiState.sessions.isEmpty() && !uiState.isLoading) {
            EmptyHistoryState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = uiState.sessions,
                    key = { it.sessionId }
                ) { session ->
                    SwipeToDeleteSessionCard(
                        session = session,
                        onDelete = { onDeleteSession(session.sessionId) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteSessionCard(
    session: SessionLogItem,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                label = "swipe_bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        SessionLogCard(session = session)
    }
}

@Composable
private fun SessionLogCard(session: SessionLogItem) {
    val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    val startDate = dateFormat.format(Date(session.startedAt))
    val duration = session.endedAt?.let { end ->
        val diff = end - session.startedAt
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(diff) % 60
        "${minutes}m ${seconds}s"
    } ?: "In progress"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = startDate,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = session.provider,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                InfoChip(label = "Messages", value = "${session.messageCount}")
                InfoChip(label = "Duration", value = duration)
            }

            if (session.summary != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = session.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No sessions yet",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your conversation history will appear here",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenPreview() {
    VzorTheme {
        HistoryScreenContent(
            uiState = HistoryUiState(
                sessions = listOf(
                    SessionLogItem(
                        sessionId = "1",
                        startedAt = System.currentTimeMillis() - 3600000,
                        endedAt = System.currentTimeMillis() - 3000000,
                        messageCount = 12,
                        provider = "Google Gemini",
                        summary = "Discussed navigation directions to the nearest coffee shop and weather forecast."
                    ),
                    SessionLogItem(
                        sessionId = "2",
                        startedAt = System.currentTimeMillis() - 86400000,
                        endedAt = System.currentTimeMillis() - 85800000,
                        messageCount = 5,
                        provider = "Anthropic Claude",
                        summary = null
                    )
                ),
                isLoading = false
            ),
            onNavigateToSettings = {},
            onDeleteSession = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HistoryScreenEmptyPreview() {
    VzorTheme {
        HistoryScreenContent(
            uiState = HistoryUiState(sessions = emptyList(), isLoading = false),
            onNavigateToSettings = {},
            onDeleteSession = {}
        )
    }
}
