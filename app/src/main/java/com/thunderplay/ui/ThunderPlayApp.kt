package com.thunderplay.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.thunderplay.ui.history.HistoryScreen
import com.thunderplay.ui.library.LibraryScreen
import com.thunderplay.ui.playlists.PlaylistsScreen
import com.thunderplay.ui.settings.SettingsScreen

private enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Library("library", "Library", Icons.Default.LibraryMusic),
    Playlists("playlists", "Playlists", Icons.AutoMirrored.Filled.QueueMusic),
    History("history", "History", Icons.Default.History),
    Settings("settings", "Settings", Icons.Default.Settings),
}

@Composable
fun ThunderPlayApp() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep a single copy of each tab and preserve its scroll position,
                                // so switching tabs does not rebuild the library list every time.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { insets ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = insets.calculateBottomPadding()),
        ) {
            NavHost(
                navController = navController,
                startDestination = Destination.Library.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.Library.route) { LibraryScreen() }
                composable(Destination.Playlists.route) { PlaylistsScreen() }
                composable(Destination.History.route) { HistoryScreen() }
                composable(Destination.Settings.route) { SettingsScreen() }
            }
        }
    }
}
