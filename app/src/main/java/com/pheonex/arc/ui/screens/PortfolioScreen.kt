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

@Composable
fun PortfolioScreen(
    allTrades: List<Trade>,
    openTrades: List<Trade>,
    dailyPnl: Double,
    winCount: Int,
    closedCount: Int
) {
    val totalPnl = allTrades.sumOf { it.profitLossSol ?: 0.0 }
    
    // FIX: Only count trades that are actually Wins or Losses. 
    // Filtered/Rejected calls (which are "CLOSED" but have no outcome) are excluded.
    val actualClosedTrades = allTrades.filter { it.outcome == TradeOutcome.WIN || it.outcome == TradeOutcome.LOSS }
    val actualClosedCount = actualClosedTrades.size
    val winRate = if (actualClosedCount > 0) winCount * 100.0 / actualClosedCount else 0.0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Your Vault", 
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold)
        }

        // Summary Cards (Groww Style)
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryCard("Realised P&L", totalPnl, Modifier.weight(1f))
                SummaryCard("Today's P&L", dailyPnl, Modifier.weight(1f))
            }
        }
        
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.1f))
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    MiniStat("Win Rate", "%.1f%%".format(winRate), GrowwGreen)
                    MiniStat("Wins", "$winCount", GrowwGreen)
                    MiniStat("Open", "${openTrades.size}", GrowwBlue)
                    MiniStat("Total", "${allTrades.size}", MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Open positions
        if (openTrades.isNotEmpty()) {
            item {
                SectionLabel("Active Positions", GrowwBlue)
            }
            items(openTrades) { TradeCard(it) }
        }

        // Trade history
        item {
            SectionLabel("Trade History", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        val closed = allTrades.filter { it.status != TradeStatus.OPEN }
        if (closed.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No past trades found", 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            items(closed) { TradeCard(it) }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
fun SectionLabel(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Text(text, 
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = color.copy(0.1f))
    }
}

@Composable
fun SummaryCard(label: String, value: Double, modifier: Modifier) {
    val isPositive = value >= 0
    val color = if (isPositive) GrowwGreen else GrowwRed
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(0.05f)),
        border = BorderStroke(1.dp, color.copy(0.2f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("${if(isPositive) "+" else ""}${"%.4f".format(value)}", 
                color = color, 
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold)
            Text("SOL", style = MaterialTheme.typography.labelSmall, color = color.copy(0.6f))
        }
    }
}

@Composable
fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TradeCard(trade: Trade) {
    val isWin  = trade.outcome == TradeOutcome.WIN
    val isLoss = trade.outcome == TradeOutcome.LOSS
    val isOpen = trade.status == TradeStatus.OPEN
    val color = when {
        isWin  -> GrowwGreen
        isLoss -> GrowwRed
        isOpen -> GrowwBlue
        else   -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Token Icon Placeholder
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = color.copy(0.1f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(trade.tokenName.take(1), color = color, fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(Modifier.weight(1f)) {
                    Text(trade.tokenName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(trade.timestamp.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                
                StatusBadge(trade)
            }
            
            Spacer(Modifier.height(16.dp))
            
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TradeMetric("Entry", "%.6f".format(trade.entryPrice))
                TradeMetric("Size", "%.2f SOL".format(trade.amountSol))
                
                if (isOpen) {
                    TradeMetric("Current", "%.6f".format(trade.entryPrice)) // Placeholder
                } else {
                    val pnl = trade.profitLossPct ?: 0.0
                    TradeMetric("PnL %", "%+.2f%%".format(pnl), if(pnl >= 0) GrowwGreen else GrowwRed)
                }
            }

            if (trade.rejectionReason != null) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = GrowwRed.copy(0.05f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("⚠ ${trade.rejectionReason}", color = GrowwRed, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }
}

@Composable
fun StatusBadge(trade: Trade) {
    val (text, color) = when {
        trade.isSimulated -> "SIMULATED" to GrowwAmber
        trade.status == TradeStatus.OPEN -> "ACTIVE" to GrowwBlue
        trade.outcome == TradeOutcome.WIN -> "PROFIT" to GrowwGreen
        trade.outcome == TradeOutcome.LOSS -> "LOSS" to GrowwRed
        else -> "CLOSED" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Surface(
        color = color.copy(0.1f), 
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(text, 
            color = color, 
            style = MaterialTheme.typography.labelSmall, 
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun TradeMetric(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
