package com.cybernomicon.grimoire.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cybernomicon.grimoire.ui.screens.*

private enum class Section(val route: String, val label: String, val icon: ImageVector) {
    Grimoire("grimoire", "Grimoire", Icons.Filled.AutoStories),
    Astral("astral", "Astral", Icons.Filled.Hub),
    Familiar("familiar", "Familiar", Icons.Filled.Chat),
    Rituals("rituals", "Rituals", Icons.Filled.AutoFixHigh),
    Chronicle("chronicle", "Chronicle", Icons.Filled.History),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrimoireApp(vm: AppViewModel) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("▚ CYBERDECK") },
                actions = {
                    val dot = when {
                        !state.connected -> "⬡ OFFLINE"
                        state.localMode -> "⬢ ON-DEVICE"
                        else -> "⬢ LINKED"
                    }
                    Text(dot, color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(end = 16.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val backStack by nav.currentBackStackEntryAsState()
                val current = backStack?.destination
                Section.entries.forEach { section ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == section.route } == true,
                        onClick = {
                            nav.navigate(section.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(section.icon, contentDescription = section.label) },
                        label = { Text(section.label) },
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Section.Grimoire.route,
            modifier = Modifier.padding(inner)
        ) {
            composable(Section.Grimoire.route) { GrimoireScreen(vm, state) }
            composable(Section.Astral.route) { AstralPlaneScreen(state) }
            composable(Section.Familiar.route) { FamiliarScreen(vm, state) }
            composable(Section.Rituals.route) { RitualsScreen(vm, state) }
            composable(Section.Chronicle.route) { ChronicleScreen(state) }
        }
    }
}
