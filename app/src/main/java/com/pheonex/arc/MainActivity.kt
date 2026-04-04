package com.pheonex.arc

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pheonex.arc.ui.screens.*
import com.pheonex.arc.ui.theme.ArcTheme
import com.pheonex.arc.ui.theme.GrowwGreen
import com.pheonex.arc.viewmodel.DashboardViewModel
import com.pheonex.arc.viewmodel.TradesViewModel
import dagger.hilt.android.AndroidEntryPoint

sealed class NavDest(val route: String, val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    object Dashboard : NavDest("dashboard", "Dashboard", Icons.Filled.Home, Icons.Outlined.Home)
    object Portfolio : NavDest("portfolio", "Portfolio", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet)
    object Logbook   : NavDest("logbook",   "Orders",    Icons.Filled.Assignment, Icons.Outlined.Assignment)
    object Settings  : NavDest("settings",  "Account",   Icons.Filled.Person, Icons.Outlined.Person)
}

val navItems = listOf(NavDest.Dashboard, NavDest.Portfolio, NavDest.Logbook, NavDest.Settings)

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            val dashVm: DashboardViewModel = hiltViewModel()
            val tradesVm: TradesViewModel  = hiltViewModel()
            val state by dashVm.uiState.collectAsStateWithLifecycle()
            
            ArcTheme(darkTheme = state.darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArcApp(dashVm, tradesVm, state)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArcApp(dashVm: DashboardViewModel, tradesVm: TradesViewModel,
           state: com.pheonex.arc.viewmodel.DashboardUiState) {
    var authenticated by remember { mutableStateOf(false) }
    var currentDest by remember { mutableStateOf<NavDest>(NavDest.Dashboard) }
    val snackbarHostState = remember { SnackbarHostState() }

    val allTrades by tradesVm.allTrades.collectAsStateWithLifecycle()
    val openTrades by tradesVm.openTrades.collectAsStateWithLifecycle()
    val dailyPnl by tradesVm.dailyPnl.collectAsStateWithLifecycle()
    val winCount by tradesVm.winCount.collectAsStateWithLifecycle()
    val closedCount by tradesVm.closedCount.collectAsStateWithLifecycle()

    if (!authenticated) {
        LockScreen(onAuthenticated = { authenticated = true })
        return
    }

    Scaffold(
        snackbarHost = { 
            SnackbarHost(snackbarHostState) { data ->
                Card(
                    modifier = Modifier.padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(data.visuals.message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    navItems.forEach { dest ->
                        val selected = currentDest == dest
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentDest = dest },
                            icon = { 
                                Icon(
                                    if (selected) dest.selectedIcon else dest.unselectedIcon, 
                                    contentDescription = dest.label
                                ) 
                            },
                            label = { 
                                Text(
                                    dest.label, 
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                ) 
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = currentDest,
                transitionSpec = {
                    fadeIn(tween(300)) + slideInVertically(initialOffsetY = { 20 }, animationSpec = tween(300)) togetherWith
                    fadeOut(tween(200))
                },
                label = "navAnim"
            ) { dest ->
                when (dest) {
                    NavDest.Dashboard -> DashboardScreen(
                        state = state,
                        allTrades = allTrades,
                        onStart  = dashVm::startEngine,
                        onStop   = dashVm::stopEngine,
                        onSimulateToggle = dashVm::setSimulateMode,
                        onDarkModeToggle = dashVm::setDarkMode,
                        onSetIp  = dashVm::setServerIp
                    )
                    NavDest.Portfolio -> PortfolioScreen(
                        allTrades, openTrades, dailyPnl, winCount, closedCount)
                    NavDest.Logbook -> LogbookScreen(allTrades)
                    NavDest.Settings -> SettingsScreen(
                        state = state,
                        onSimulateToggle = dashVm::setSimulateMode,
                        onDarkModeToggle = dashVm::setDarkMode,
                        onSetIp  = dashVm::setServerIp,
                        onSeedDemo = tradesVm::seedDemoData
                    )
                }
            }

            // Error notification
            state.errorMsg?.let { msg ->
                LaunchedEffect(msg) {
                    snackbarHostState.showSnackbar(
                        message = "Notice: $msg",
                        duration = SnackbarDuration.Short
                    )
                    dashVm.clearError()
                }
            }
        }
    }
}
