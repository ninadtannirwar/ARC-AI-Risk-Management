package com.pheonex.arc.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pheonex.arc.data.model.*
import com.pheonex.arc.ui.theme.*

enum class LogFilter { ALL, PROFIT, LOSS, REJECTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookScreen(trades: List<Trade>) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }

    val filtered = when (filter) {
        LogFilter.ALL      -> trades
        LogFilter.REJECTED -> trades.filter { it.rejectionReason != null }
        LogFilter.LOSS     -> trades.filter { it.outcome == TradeOutcome.LOSS }
        LogFilter.PROFIT   -> trades.filter { it.outcome == TradeOutcome.WIN }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orders", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                // Modern Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogFilter.values().forEach { f ->
                        val selected = filter == f
                        FilterChip(
                            selected = selected,
                            onClick = { filter = f },
                            label = { Text(f.name.lowercase().capitalize()) },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(0.1f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = Color.Transparent,
                                selectedBorderColor = MaterialTheme.colorScheme.primary.copy(0.5f),
                                borderWidth = 1.dp
                            )
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                EmptyOrdersPlaceholder()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered) { trade ->
                        ModernOrderEntry(trade)
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
fun ModernOrderEntry(trade: Trade) {
    val statusColor = when {
        trade.outcome == TradeOutcome.WIN  -> GrowwGreen
        trade.outcome == TradeOutcome.LOSS -> GrowwRed
        trade.rejectionReason != null      -> GrowwAmber
        else                               -> GrowwBlue
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.05f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = statusColor.copy(0.1f),
                    shape = CircleShape,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (trade.rejectionReason != null) Icons.Default.Block else Icons.Default.CurrencyExchange,
                        null,
                        tint = statusColor,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(trade.tokenName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (trade.rejectionReason != null) "Rejected" else "Completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    trade.profitLossSol?.let {
                        Text(
                            "%+.4f SOL".format(it),
                            color = if (it >= 0) GrowwGreen else GrowwRed,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            
            if (trade.rejectionReason != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Reason: ${trade.rejectionReason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(0.3f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyOrdersPlaceholder() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ReceiptLong, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("No orders yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Your trade executions and rejected signals will appear here.", 
            style = MaterialTheme.typography.bodySmall, 
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

private fun String.capitalize() = this.lowercase().replaceFirstChar { it.uppercase() }
