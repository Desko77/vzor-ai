package com.vzor.ai.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vzor.ai.ui.chat.ChatScreen
import com.vzor.ai.ui.history.HistoryScreen
import com.vzor.ai.ui.home.HomeScreen
import com.vzor.ai.ui.logs.LogsScreen
import com.vzor.ai.ui.settings.SettingsScreen
import com.vzor.ai.ui.settings.SettingsViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import com.vzor.ai.ui.translation.TranslationScreen

object Routes {
    const val HOME = "home"
    const val CHAT = "chat"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
    const val TRANSLATION = "translation"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "Home", Icons.Default.Home),
    BottomNavItem(Routes.CHAT, "Chat", Icons.Default.Chat),
    BottomNavItem(Routes.TRANSLATION, "Translate", Icons.Default.Translate),
    BottomNavItem(Routes.HISTORY, "History", Icons.Default.History)
)

private val logsNavItem = BottomNavItem(Routes.LOGS, "Logs", Icons.Default.Terminal)

@Composable
fun VzorNavGraph(
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val settingsState by settingsViewModel.uiState.collectAsState()
    val activeNavItems = remember(settingsState.developerMode) {
        if (settingsState.developerMode) bottomNavItems + logsNavItem else bottomNavItems
    }

    // Only show bottom bar on main tabs (not on Settings)
    val showBottomBar = currentDestination?.route in activeNavItems.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    activeNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    // Pop up to the start destination to avoid stacking
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                    onNavigateToTranslation = { navController.navigate(Routes.TRANSLATION) }
                )
            }

            composable(Routes.CHAT) {
                ChatScreen(
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.HISTORY) {
                HistoryScreen(
                    onNavigateToSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }

            composable(Routes.TRANSLATION) {
                TranslationScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Routes.LOGS) {
                LogsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
