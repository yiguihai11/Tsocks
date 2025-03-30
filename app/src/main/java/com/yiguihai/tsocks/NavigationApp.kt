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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val titleResId: Int, val icon: @Composable () -> Unit) {
    val title: String @Composable get() = stringResource(id = titleResId)
    
    data object Home : Screen("home", R.string.home, { 
        Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.home)) 
    })
    data object HevSocks5Tunnel : Screen("hevsocks5tunnel", R.string.app_name, { 
        Icon(Icons.Outlined.Settings, contentDescription = "HevSocks5Tunnel")
    })
    data object Shadowsocks : Screen("shadowsocks", R.string.shadowsocks, { 
        Icon(Icons.Outlined.Settings, contentDescription = "Shadowsocks") 
    })
    data object ProxySplit : Screen("proxysplit", R.string.proxy_split, { 
        Icon(Icons.Outlined.FilterAlt, contentDescription = stringResource(R.string.proxy_split)) 
    })
    data object Hotspot : Screen("hotspot", R.string.hotspot_sharing, { 
        Icon(Icons.Outlined.WifiTethering, contentDescription = stringResource(R.string.hotspot_sharing)) 
    })
    data object OptimalIp : Screen("optimalip", R.string.optimal_ip_speed, { 
        Icon(Icons.Outlined.Speed, contentDescription = stringResource(R.string.optimal_ip_speed)) 
    })
    data object About : Screen("about", R.string.about, { 
        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.about)) 
    })
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
    val menuContentDescription = stringResource(R.string.menu)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = menuContentDescription)
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) { content() }
    }
}
