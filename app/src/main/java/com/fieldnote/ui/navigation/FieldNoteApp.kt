package com.fieldnote.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fieldnote.MainViewModel
import com.fieldnote.ui.screen.CalendarScreen
import com.fieldnote.ui.screen.NoteScreen
import com.fieldnote.ui.screen.SettingsScreen
import com.fieldnote.ui.screen.TodoScreen

private enum class AppDestination(val route: String, val label: String) {
    Note("note", "노트"),
    Todo("todo", "할 일"),
    Calendar("calendar", "달력"),
    Settings("settings", "설정")
}

@Composable
fun FieldNoteApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val featureStatuses by viewModel.featureStatuses.collectAsState(initial = emptyList())

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        label = { Text(destination.label) },
                        icon = { Text(destination.label.take(1)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val tabletMode = maxWidth >= 600.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = if (tabletMode) 24.dp else 12.dp, vertical = 12.dp)
            ) {
                NavHost(navController = navController, startDestination = AppDestination.Note.route) {
                    composable(AppDestination.Note.route) {
                        NoteScreen(viewModel = viewModel, tabletMode = tabletMode)
                    }
                    composable(AppDestination.Todo.route) {
                        TodoScreen(viewModel = viewModel, tabletMode = tabletMode)
                    }
                    composable(AppDestination.Calendar.route) {
                        CalendarScreen(todos = viewModel.todos, tabletMode = tabletMode)
                    }
                    composable(AppDestination.Settings.route) {
                        SettingsScreen(
                            uiState = viewModel.uiState,
                            featureStatuses = featureStatuses,
                            syncState = viewModel.syncState,
                            updateState = viewModel.updateState,
                            onSyncFolderPicked = viewModel::onSyncFolderPicked,
                            onSyncFolderDisconnected = viewModel::disconnectSyncFolder,
                            onSyncNow = viewModel::syncNow,
                            onClearConflicts = viewModel::clearConflicts,
                            onConflictPolicyChange = viewModel::setConflictPolicy,
                            onInstallUpdate = viewModel::installUpdate,
                            tabletMode = tabletMode
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScreenFrame(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium)
        Box(modifier = Modifier.padding(top = 16.dp)) {
            content()
        }
    }
}



