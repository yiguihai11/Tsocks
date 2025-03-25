package com.yiguihai.tsocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    data object Home : Screen("home", "主页", { Icon(Icons.Filled.Home, contentDescription = "主页") })
    data object HevSocks5Tunnel : Screen("hevsocks5tunnel", "HevSocks5Tunnel", { Icon(Icons.Outlined.Settings, contentDescription = "HevSocks5Tunnel") })
    data object Shadowsocks : Screen("shadowsocks", "Shadowsocks", { Icon(Icons.Outlined.Settings, contentDescription = "Shadowsocks") })
    data object ProxySplit : Screen("proxysplit", "代理分流", { Icon(Icons.Outlined.FilterAlt, contentDescription = "代理分流") })
    data object Hotspot : Screen("hotspot", "热点共享", { Icon(Icons.Outlined.WifiTethering, contentDescription = "热点共享") })
    data object OptimalIp : Screen("optimalip", "优选IP测速", { Icon(Icons.Outlined.Speed, contentDescription = "优选IP测速") })
    data object About : Screen("about", "关于", { Icon(Icons.Filled.Info, contentDescription = "关于") })
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val screens = listOf(
        Screen.Home,
        Screen.HevSocks5Tunnel,
        Screen.Shadowsocks,
        Screen.ProxySplit,
        Screen.Hotspot,
        Screen.OptimalIp,
        Screen.About
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                screens.forEach { screen ->
                    val currentRoute by navController.currentBackStackEntryAsState()
                    NavigationDrawerItem(
                        icon = { screen.icon() },
                        label = { Text(screen.title) },
                        selected = currentRoute?.destination?.route == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        NavHost(navController = navController, startDestination = Screen.Home.route) {
            composable(Screen.Home.route) {
                PageScaffold(screen = Screen.Home, drawerState = drawerState) { HomeScreen() }
            }
            composable(Screen.HevSocks5Tunnel.route) {
                PageScaffold(screen = Screen.HevSocks5Tunnel, drawerState = drawerState) { HevSocks5TunnelScreen() }
            }
            composable(Screen.Shadowsocks.route) {
                PageScaffold(screen = Screen.Shadowsocks, drawerState = drawerState) { ShadowsocksScreen() }
            }
            composable(Screen.ProxySplit.route) {
                PageScaffold(screen = Screen.ProxySplit, drawerState = drawerState) { ProxySplitScreen() }
            }
            composable(Screen.Hotspot.route) {
                PageScaffold(screen = Screen.Hotspot, drawerState = drawerState) { HotspotScreen() }
            }
            composable(Screen.OptimalIp.route) {
                PageScaffold(screen = Screen.OptimalIp, drawerState = drawerState) { OptimalIpScreen() }
            }
            composable(Screen.About.route) {
                PageScaffold(screen = Screen.About, drawerState = drawerState) { AboutScreen() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(screen: Screen, drawerState: DrawerState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "菜单")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) { content() }
    }
}
