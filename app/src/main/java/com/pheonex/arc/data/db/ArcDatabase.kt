package com.pheonex.arc.data.db

import androidx.room.*
import com.pheonex.arc.data.model.Trade
import com.pheonex.arc.data.model.PortfolioSnapshot
import com.pheonex.arc.data.model.TradeOutcome
import com.pheonex.arc.data.model.TradeStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY timestamp DESC")
    fun getAllTrades(): Flow<List<Trade>>

    @Query("SELECT * FROM trades WHERE status = 'OPEN' ORDER BY timestamp DESC")
    fun getOpenTrades(): Flow<List<Trade>>

    @Query("SELECT * FROM trades WHERE outcome = 'LOSS' OR rejectionReason IS NOT NULL ORDER BY timestamp DESC")
    fun getRejectedAndLosses(): Flow<List<Trade>>

    @Query("SELECT * FROM trades WHERE outcome = 'WIN' ORDER BY timestamp DESC")
    fun getWinTrades(): Flow<List<Trade>>

    @Query("SELECT COALESCE(SUM(profitLossSol),0) FROM trades WHERE DATE(timestamp/1000,'unixepoch') = DATE('now')")
    fun getDailyPnl(): Flow<Double>

    @Query("SELECT COUNT(*) FROM trades WHERE outcome = 'WIN'")
    fun getWinCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM trades WHERE status = 'CLOSED'")
    fun getClosedCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trade: Trade): Long

    @Query("SELECT * FROM trades WHERE tradeId = :tid LIMIT 1")
    suspend fun getByTradeId(tid: String): Trade?

    @Update
    suspend fun update(trade: Trade)

    @Delete
    suspend fun delete(trade: Trade)
}

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolio_snapshots ORDER BY timestamp DESC LIMIT 30")
    fun getSnapshots(): Flow<List<PortfolioSnapshot>>

    @Insert
    suspend fun insert(snapshot: PortfolioSnapshot)
}

/**
 * CHANGE: Bumped version 2 → 3.
 *
 * Version 3 adds:
 *   - `tradeId` STRING for backend sync mapping.
 */
@Database(entities = [Trade::class, PortfolioSnapshot::class], version = 3, exportSchema = false)
abstract class ArcDatabase : RoomDatabase() {
    abstract fun tradeDao(): TradeDao
    abstract fun portfolioDao(): PortfolioDao
}
