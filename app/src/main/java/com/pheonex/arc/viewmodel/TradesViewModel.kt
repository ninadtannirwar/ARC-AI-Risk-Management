package com.pheonex.arc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pheonex.arc.data.db.TradeDao
import com.pheonex.arc.data.model.Trade
import com.pheonex.arc.data.model.TradeOutcome
import com.pheonex.arc.data.model.TradeStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TradesViewModel @Inject constructor(
    private val tradeDao: TradeDao
) : ViewModel() {

    val allTrades: StateFlow<List<Trade>> = tradeDao.getAllTrades()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val openTrades: StateFlow<List<Trade>> = tradeDao.getOpenTrades()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rejectedAndLosses: StateFlow<List<Trade>> = tradeDao.getRejectedAndLosses()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dailyPnl: StateFlow<Double> = tradeDao.getDailyPnl()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val winCount: StateFlow<Int> = tradeDao.getWinCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val closedCount: StateFlow<Int> = tradeDao.getClosedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Seed demo data for hackathon demo: 18 calls, 8 trades (7 win, 1 loss), 10 filtered
    fun seedDemoData() {
        viewModelScope.launch {
            if (allTrades.value.size > 15) return@launch 
            
            val now = System.currentTimeMillis()
            val hour = 3600000L
            
            val demos = mutableListOf<Trade>()

            // 7 WINNING TRADES
            val wins = listOf(
                Pair("PHEONEX", 0.045), Pair("SOL-MOON", 0.012), Pair("ARCDOG", 0.008),
                Pair("GALAXY", 0.021), Pair("NEBULA", 0.015), Pair("ORBIT", 0.033), Pair("COMET", 0.009)
            )
            wins.forEachIndexed { i, (name, profit) ->
                demos.add(Trade(
                    tokenName=name, tokenAddress="win$i", entryPrice=0.001, exitPrice=0.0015,
                    amountSol=0.1, profitLossSol=profit, profitLossPct=15.0,
                    status=TradeStatus.CLOSED, outcome=TradeOutcome.WIN,
                    t1Score=0.75 + (i*0.02), t2Score=0.68 + (i*0.01), aiConfidence=0.92, tier="T3",
                    timestamp = now - (i * hour)
                ))
            }

            // 1 LOSS TRADE
            demos.add(Trade(
                tokenName="RUG-ME", tokenAddress="loss1", entryPrice=0.002, exitPrice=0.0014,
                amountSol=0.1, profitLossSol=-0.015, profitLossPct=-30.0,
                status=TradeStatus.CLOSED, outcome=TradeOutcome.LOSS,
                t1Score=0.51, t2Score=0.45, aiConfidence=0.86, tier="T3",
                timestamp = now - (8 * hour)
            ))

            // 10 FILTERED / REJECTED CALLS (to make total 18 calls)
            val filters = listOf(
                "Low Liquidity", "Rug detected", "Dev reputation low", "Top 10 holders > 60%", 
                "Bonding curve stagnant", "High slippage", "Math score < 0.3", "Market cap too low",
                "AI confidence < 0.85", "Consecutive losses stop"
            )
            filters.forEachIndexed { i, reason ->
                demos.add(Trade(
                    tokenName="SCAN-${100+i}", tokenAddress="filt$i", entryPrice=0.0,
                    amountSol=0.0, rejectionReason=reason,
                    status=TradeStatus.CLOSED, outcome=TradeOutcome.PENDING,
                    t1Score=0.1 + (i*0.05), t2Score=0.1, aiConfidence=0.2, tier="T1",
                    timestamp = now - ((9 + i) * hour)
                ))
            }

            demos.forEach { tradeDao.insert(it) }
        }
    }
}
