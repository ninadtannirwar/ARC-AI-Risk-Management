package com.pheonex.arc.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TradeStatus { OPEN, CLOSED, SIMULATED, CRASHED }
enum class TradeOutcome { WIN, LOSS, BREAK_EVEN, PENDING }
enum class TierResult { PASS, FAIL, PENDING }

/**
 * CHANGE 1: Added `tier` and `aiConfidence` to the Trade entity.
 *
 * `tier` — the tier badge string from the backend CSV (e.g. "T1", "T2", "T3").
 *   Displayed as a chip in PortfolioScreen / LogbookScreen.
 *   The backend stores this in the CSV `tier` column alongside t1_score / t2_score.
 *
 * `aiConfidence` — explicit mapping of the backend's `ai_confidence` CSV column.
 *   The existing `t3Score` field was being used for this value in seedDemoData()
 *   and is kept for backward compatibility. `aiConfidence` is the canonical
 *   backend name; prefer it for any new records.
 *
 * CHANGE 2: Added CRASHED to TradeStatus enum.
 *   The backend calls store.mark_open_as_crashed() on unexpected shutdown,
 *   writing status="CRASHED" to the CSV. Without this variant Room throws
 *   an IllegalArgumentException when reading back those rows.
 *
 * NOTE: Adding columns bumps the Room DB schema — see ArcDatabase.kt (version 2).
 *   The existing fallbackToDestructiveMigration() handles the upgrade on first
 *   install after this change.
 */
@Entity(tableName = "trades")
data class Trade(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tradeId: String? = null,     // ← new: backend UUID / identifier
    val tokenAddress: String,
    val tokenName: String,
    val entryPrice: Double,
    val exitPrice: Double? = null,
    val amountSol: Double,
    val profitLossSol: Double? = null,
    val profitLossPct: Double? = null,
    val status: TradeStatus = TradeStatus.OPEN,
    val outcome: TradeOutcome = TradeOutcome.PENDING,
    val isSimulated: Boolean = false,
    val t1Score: Double = 0.0,
    val t2Score: Double = 0.0,
    val t3Score: Double = 0.0,       // legacy alias for aiConfidence — kept for seedDemoData compat
    val aiConfidence: Double = 0.0,  // ← new: canonical backend ai_confidence field
    val tier: String? = null,        // ← new: tier badge ("T1" | "T2" | "T3")
    val rejectionReason: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "portfolio_snapshots")
data class PortfolioSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val totalSol: Double,
    val dailyPnl: Double,
    val totalTrades: Int,
    val winRate: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class EngineStats(
    val engineRunning: Boolean = false,
    val mode: String = "LIVE",
    val tradesOpen: Int = 0,
    val tradesClosed: Int = 0,
    val winRate: Double = 0.0,
    val avgProfitPct: Double = 0.0,
    val dailyPnlSol: Double = 0.0,
    val aiCallsToday: Int = 0,
    val aiCallsLimit: Int = 20,
    val avgT1Score: Double = 0.0,
    val avgT2Score: Double = 0.0,
    val avgAiConfidence: Double = 0.0,
    val walletSol: Double = 0.0,
    // Simulate-mode: profitable trade progress toward daily target
    val profitableTrades: Int = 0,
    val targetProfitableTrades: Int = 5,
)

data class LogEntry(
    val timestamp: String,
    val message: String,
    val type: LogType
)

enum class LogType {
    SNIPE, PROFIT, LOSS, RUG, REJECTED, PASS, WARNING, AI, MATH, MARKET, SECURITY, WAITING, INFO
}
