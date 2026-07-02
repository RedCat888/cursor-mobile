package com.cursormobile.ui.nav

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cursormobile.data.prefs.AuthStore
import com.cursormobile.ui.agents.AgentChatScreen
import com.cursormobile.ui.agents.AgentsScreen
import com.cursormobile.ui.agents.IdeChatScreen
import com.cursormobile.ui.files.FilesScreen
import com.cursormobile.ui.mcp.McpScreen
import com.cursormobile.ui.pairing.PairingScreen
import com.cursormobile.ui.settings.SettingsScreen
import com.cursormobile.ui.terminal.TerminalScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Agents : Dest("agents", "Agents", Icons.Outlined.Bolt)
    data object Files : Dest("files", "Files", Icons.Outlined.Folder)
    data object Terminal : Dest("terminal", "Terminal", Icons.Outlined.Terminal)
    data object Mcp : Dest("mcp", "MCP", Icons.Outlined.Extension)
    data object Settings : Dest("settings", "Settings", Icons.Outlined.Settings)
}

private val tabs = listOf(Dest.Agents, Dest.Files, Dest.Terminal, Dest.Mcp, Dest.Settings)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AuthEntryPoint {
    fun authStore(): AuthStore
}

@Composable
fun CursorMobileNavHost(initialIntent: Intent? = null) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val auth = remember {
        EntryPointAccessors.fromApplication(ctx.applicationContext, AuthEntryPoint::class.java).authStore()
    }
    val activePair by auth.activePairFlow.collectAsState()
    val needsPairing = activePair == null

    LaunchedEffect(initialIntent) {
        val data = initialIntent?.data?.toString() ?: return@LaunchedEffect
        if (data.startsWith("cm1://pair")) {
            nav.navigate("pair?qr=${android.net.Uri.encode(data)}")
        }
    }

    // When pairing completes mid-session, transition the nav graph to the main routes.
    LaunchedEffect(needsPairing) {
        if (!needsPairing) {
            nav.navigate(Dest.Agents.route) { popUpTo(0) }
        }
    }

    Scaffold(
        bottomBar = { if (!needsPairing) BottomBar(nav) },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = if (needsPairing) "pair?qr={qr}" else Dest.Agents.route,
            modifier = Modifier.fillMaxSize().padding(inner),
        ) {
            graph(nav)
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController) {
    val back by nav.currentBackStackEntryAsState()
    val current = back?.destination?.route
    NavigationBar {
        tabs.forEach { tab ->
            val selected = current?.startsWith(tab.route) == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}

private fun NavGraphBuilder.graph(nav: NavHostController) {
    composable(Dest.Agents.route) {
        AgentsScreen(
            onOpen = { nav.navigate("agent/$it") },
            onOpenIde = { nav.navigate("ide/$it") },
        )
    }
    composable("agent/{id}") { backStack ->
        val id = backStack.arguments?.getString("id") ?: return@composable
        AgentChatScreen(agentId = id, onBack = { nav.popBackStack() })
    }
    composable("ide/{id}") { backStack ->
        val id = backStack.arguments?.getString("id") ?: return@composable
        IdeChatScreen(
            id = id,
            onBack = { nav.popBackStack() },
            onOpenAgent = { agentId ->
                nav.navigate("agent/$agentId") {
                    popUpTo("ide/$id") { inclusive = true }
                }
            },
        )
    }
    composable(Dest.Files.route) { FilesScreen() }
    composable(Dest.Terminal.route) { TerminalScreen() }
    composable(Dest.Mcp.route) { McpScreen() }
    composable(Dest.Settings.route) {
        SettingsScreen(onUnpair = { nav.navigate("pair?qr={qr}") { popUpTo(0) } })
    }
    composable("pair?qr={qr}") { backStack ->
        val qr = backStack.arguments?.getString("qr")
        PairingScreen(initialQr = qr, onPaired = { nav.navigate(Dest.Agents.route) { popUpTo(0) } })
    }
}
