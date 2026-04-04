package com.pheonex.arc.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pheonex.arc.data.model.*
import com.pheonex.arc.ui.theme.*
import com.pheonex.arc.viewmodel.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    allTrades: List<Trade>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSimulateToggle: (Boolean) -> Unit,
    onDarkModeToggle: (Boolean) -> Unit,
    onSetIp: (String) -> Unit
) {
    var showIpDialog by remember { mutableStateOf(state.serverIp.isBlank()) }
    var showKillDialog by remember { mutableStateOf(false) }

    if (showIpDialog) {
        ConnectDialog(current = state.serverIp, onConfirm = { ip ->
            onSetIp(ip); showIpDialog = false
        })
    }

    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { showKillDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Stop all trades?", fontWeight = FontWeight.Bold) },
            text = { Text("This will immediately stop the engine and close your active positions to protect your balance.") },
            confirmButton = {
                Button(onClick = { onStop(); showKillDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = GrowwRed)) {
                    Text("Stop Now", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = { TextButton(onClick = { showKillDialog = false }) { Text("Cancel") } }
        )
    }

    val actualClosedTrades = allTrades.filter { it.outcome == TradeOutcome.WIN || it.outcome == TradeOutcome.LOSS }
    val actualWinCount = actualClosedTrades.count { it.outcome == TradeOutcome.WIN }
    val actualWinRate = if (actualClosedTrades.isNotEmpty()) (actualWinCount.toDouble() / actualClosedTrades.size.toDouble()) * 100.0 else 0.0

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = GrowwGreen.copy(0.2f)
                        ) {
                            Icon(Icons.Default.AutoGraph, null, tint = GrowwGreen, modifier = Modifier.padding(6.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("Dashboard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                },
                actions = {
                    IconButton(onClick = { onDarkModeToggle(!state.darkMode) }) {
                        Icon(if (state.darkMode) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null)
                    }
                    IconButton(onClick = { showIpDialog = true }) {
                        Icon(Icons.Outlined.CloudQueue, null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                PortfolioHeader(state, allTrades)
            }
            
            item {
                HumanizedEngineStatus(state)
            }

            item {
                SectionHeader("Trading Insights")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InsightCard(
                        label = "Open Trades",
                        value = "${state.stats.tradesOpen}",
                        icon = Icons.Default.Inventory2,
                        modifier = Modifier.weight(1f)
                    )
                    InsightCard(
                        label = "AI Confidence",
                        value = "${(state.stats.avgAiConfidence * 100).toInt()}%",
                        icon = Icons.Default.Psychology,
                        modifier = Modifier.weight(1f)
                    )
                    InsightCard(
                        label = "Win Rate",
                        value = "${"%.1f".format(actualWinRate)}%",
                        icon = Icons.Default.EmojiEvents,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                SectionHeader("Quick Actions")
                MainActions(state, onStart, onStop = { showKillDialog = true }, onSimulateToggle)
            }

            item {
                SectionHeader("Recent Activity")
            }

            if (state.logs.isEmpty()) {
                item {
                    EmptyActivityPlaceholder()
                }
            } else {
                items(state.logs.take(5)) { log ->
                    HumanizedLogItem(log)
                }
            }
            
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
fun MainActions(state: DashboardUiState, onStart: () -> Unit, onStop: () -> Unit, onSimulate: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                enabled = !state.stats.engineRunning && !state.loading,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GrowwGreen)
            ) {
                if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                else Text("START ENGINE", fontWeight = FontWeight.Bold)
            }
            
            OutlinedButton(
                onClick = onStop,
                enabled = state.stats.engineRunning,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, GrowwRed),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = GrowwRed)
            ) {
                Text("STOP ALL", fontWeight = FontWeight.Bold)
            }
        }
        
        Card(
            onClick = { onSimulate(!state.simulateMode) },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if(state.simulateMode) GrowwAmber.copy(0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
            )
        ) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Science, null, tint = if(state.simulateMode) GrowwAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Practice Mode", fontWeight = FontWeight.Bold)
                    Text("Trade with virtual money", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = state.simulateMode, onCheckedChange = onSimulate)
            }
        }
    }
}

@Composable
fun PortfolioHeader(state: DashboardUiState, allTrades: List<Trade>) {
    val stats = state.stats
    // Calculate total profit from all trades to show a dynamic balance
    val totalProfit = allTrades.sumOf { it.profitLossSol ?: 0.0 }
    
    // Display simulated balance if in Practice Mode, otherwise real balance
    // Start with 10 SOL and add/subtract based on trade performance
    val displayBalance = if (state.simulateMode) {
        10.0 + totalProfit
    } else {
        stats.walletSol
    }

    // FIX: "Today's Profit" was showing backend-only data.
    // In Simulation Mode, we should calculate it from the list of trades matching 'today'.
    val todayStart = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
    }.timeInMillis
    
    val todayProfit = if (state.simulateMode) {
        allTrades.filter { it.timestamp >= todayStart }.sumOf { it.profitLossSol ?: 0.0 }
    } else {
        stats.dailyPnlSol
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Simplified Background Graph Decoration
            Canvas(modifier = Modifier.matchParentSize().alpha(0.1f)) {
                val path = Path().apply {
                    moveTo(0f, size.height * 0.7f)
                    cubicTo(size.width * 0.2f, size.height * 0.8f, size.width * 0.4f, size.height * 0.4f, size.width * 0.6f, size.height * 0.6f)
                    cubicTo(size.width * 0.8f, size.height * 0.8f, size.width * 0.9f, size.height * 0.2f, size.width, size.height * 0.3f)
                }
                drawPath(path, Color.White, style = Stroke(width = 4.dp.toPx()))
            }

            Column(Modifier.padding(24.dp)) {
                Text(
                    text = if (state.simulateMode) "Practice Balance" else "Total Balance",
                    color = Color.White.copy(0.7f), 
                    style = MaterialTheme.typography.labelLarge
                )
                Text("${"%.4f".format(displayBalance)} SOL", 
                    style = MaterialTheme.typography.headlineLarge, 
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White)
                
                Spacer(Modifier.height(20.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(0.15f))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Today's Profit", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
                        Text("${if(todayProfit >= 0) "+" else ""}${"%.4f".format(todayProfit)} SOL",
                            color = Color.White,
                            fontWeight = FontWeight.Bold)
                    }
                    VerticalDivider(color = Color.White.copy(0.1f), modifier = Modifier.height(30.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Active Mode", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelSmall)
                        Text(if (state.simulateMode) "PRACTICE" else "LIVE", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HumanizedEngineStatus(state: DashboardUiState) {
    val running = state.stats.engineRunning
    val wsConnected = state.wsConnected
    val isError = state.errorMsg != null
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isError -> GrowwRed.copy(0.1f)
                !wsConnected -> MaterialTheme.colorScheme.surfaceVariant.copy(0.3f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(1.dp, when {
            isError -> GrowwRed.copy(0.3f)
            !wsConnected -> MaterialTheme.colorScheme.outline.copy(0.2f)
            else -> MaterialTheme.colorScheme.outline.copy(0.1f)
        })
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                if (running && !isError) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = GrowwGreen,
                        strokeWidth = 3.dp
                    )
                }
                Icon(
                    when {
                        isError -> Icons.Default.ErrorOutline
                        !wsConnected -> Icons.Default.CloudOff
                        running -> Icons.Default.TrendingUp
                        else -> Icons.Default.PowerSettingsNew
                    },
                    contentDescription = null,
                    tint = when {
                        isError -> GrowwRed
                        !wsConnected -> GrowwAmber
                        running -> GrowwGreen
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        isError -> "Connection Error"
                        !wsConnected -> "Connecting to Backend..."
                        running -> "Engine Active"
                        else -> "Engine Standby"
                    },
                    fontWeight = FontWeight.Bold,
                    color = if(isError) GrowwRed else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when {
                        isError -> state.errorMsg ?: "Unknown error"
                        !wsConnected -> "Searching for ${state.serverIp}:${state.serverPort}"
                        running -> "AI is monitoring $0 market cap tokens"
                        else -> "Ready to launch on ${state.serverIp}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun InsightCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.4f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(12.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HumanizedLogItem(log: LogEntry) {
    val icon = when (log.type) {
        LogType.SNIPE -> Icons.Default.ShoppingBag
        LogType.PROFIT -> Icons.Default.AddChart
        LogType.LOSS -> Icons.Default.History
        LogType.WARNING -> Icons.Default.ReportProblem
        else -> Icons.Default.Info
    }
    
    val color = when (log.type) {
        LogType.SNIPE, LogType.PROFIT -> GrowwGreen
        LogType.LOSS, LogType.RUG -> GrowwRed
        LogType.WARNING -> GrowwAmber
        else -> GrowwBlue
    }
    
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = color.copy(0.1f),
            shape = CircleShape,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(log.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(log.timestamp, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyActivityPlaceholder() {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.TipsAndUpdates, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("Your activity will appear here", fontWeight = FontWeight.Bold)
        Text("Tap 'Start Engine' to begin automated trading", 
            style = MaterialTheme.typography.bodySmall, 
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ConnectDialog(current: String, onConfirm: (String) -> Unit) {
    var ip by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = { },
        shape = RoundedCornerShape(28.dp),
        title = { Text("Connect to Server", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column {
                Text("Enter your backend server IP to start syncing trades.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("Server IP") },
                    placeholder = { Text("e.g. 192.168.1.5") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(ip) }, shape = RoundedCornerShape(12.dp)) { 
                Text("Connect Now", fontWeight = FontWeight.Bold) 
            }
        }
    )
}
