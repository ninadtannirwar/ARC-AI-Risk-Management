package com.pheonex.arc.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pheonex.arc.data.db.*
import com.pheonex.arc.data.model.*
import com.pheonex.arc.data.repository.PrefsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

data class DashboardUiState(
    val stats: EngineStats = EngineStats(),
    val logs: List<LogEntry> = emptyList(),
    val wsConnected: Boolean = false,
    val simulateMode: Boolean = false,
    val darkMode: Boolean = true,
    val serverIp: String = "",
    val serverPort: String = PrefsRepository.DEFAULT_PORT,
    val loading: Boolean = false,
    val errorMsg: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val api: ArcApiService,
    private val prefs: PrefsRepository,
    private val okHttp: OkHttpClient,
    private val tradeDao: com.pheonex.arc.data.db.TradeDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var wsManager: ArcWebSocketManager? = null
    private val _logs = mutableListOf<LogEntry>()
    private var currentWsUrl: String? = null

    init {
        viewModelScope.launch {
            combine(prefs.simulateMode, prefs.darkMode, prefs.serverIp, prefs.serverPort)
            { sim, dark, ip, port -> Quad(sim, dark, ip, port) }
            .collect { (sim, dark, ip, port) ->
                val oldIp   = _uiState.value.serverIp
                val oldPort = _uiState.value.serverPort
                _uiState.update { it.copy(simulateMode = sim, darkMode = dark, serverIp = ip, serverPort = port) }

                if (ip.isNotBlank() && (ip != oldIp || port != oldPort || wsManager == null)) {
                    connectWs(ip, port)
                }
            }
        }
        startPolling()
    }

    // Simple data holder for combine() with 4 args
    private data class Quad<A,B,C,D>(val a: A, val b: B, val c: C, val d: D)

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                if (uiState.value.serverIp.isNotBlank()) {
                    fetchStats()
                }
                delay(10_000)
            }
        }
    }

    private suspend fun fetchStats() {
        try {
            val s = api.getStats()
            _uiState.update { st -> st.copy(
                stats = EngineStats(
                    engineRunning = s.engineRunning, mode = s.mode,
                    tradesOpen = s.tradesOpen, tradesClosed = s.tradesClosed,
                    winRate = s.winRate, avgProfitPct = s.avgProfitPct,
                    dailyPnlSol = s.dailyPnlSol, aiCallsToday = s.aiCallsToday,
                    aiCallsLimit = s.aiCallsLimit, avgT1Score = s.avgT1Score,
                    avgT2Score = s.avgT2Score, avgAiConfidence = s.avgAiConfidence,
                    walletSol = s.walletSol,
                    profitableTrades = s.profitableTrades,
                    targetProfitableTrades = s.targetProfitableTrades,
                ),
                errorMsg = null // Clear any previous error on success
            )}

            // Sync trades to Room
            syncTradesWithBackend()
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMsg = "Stats error: ${e.message}") }
        }
    }

    private suspend fun syncTradesWithBackend() {
        try {
            val backendTrades = api.getTrades()
            backendTrades.forEach { bt ->
                val existing = if (bt.tradeId.isNotBlank()) tradeDao.getByTradeId(bt.tradeId) else null
                
                val status = when (bt.status) {
                    "OPEN" -> TradeStatus.OPEN
                    "CLOSED" -> TradeStatus.CLOSED
                    "CRASHED" -> TradeStatus.CRASHED
                    else -> TradeStatus.SIMULATED
                }

                val outcome = when {
                    (bt.netPnlSol ?: 0.0) > 0 -> TradeOutcome.WIN
                    (bt.netPnlSol ?: 0.0) < 0 -> TradeOutcome.LOSS
                    bt.status == "OPEN" -> TradeOutcome.PENDING
                    else -> TradeOutcome.BREAK_EVEN
                }

                val trade = Trade(
                    id = existing?.id ?: 0,
                    tradeId = bt.tradeId,
                    tokenAddress = bt.mint,
                    tokenName = bt.symbol,
                    entryPrice = bt.entryMc ?: 0.0,
                    exitPrice = bt.exitMc,
                    amountSol = bt.investmentSol ?: 0.0,
                    profitLossSol = bt.netPnlSol,
                    profitLossPct = if ((bt.investmentSol ?: 0.0) != 0.0) (bt.netPnlSol ?: 0.0) / bt.investmentSol!! * 100.0 else 0.0,
                    status = status,
                    outcome = outcome,
                    isSimulated = bt.status == "SIMULATED",
                    t1Score = bt.t1Score ?: 0.0,
                    t2Score = bt.t2Score ?: 0.0,
                    aiConfidence = bt.aiConfidence ?: 0.0,
                    tier = when {
                        (bt.aiConfidence ?: 0.0) > 0 -> "T3"
                        (bt.t2Score ?: 0.0) > 0 -> "T2"
                        else -> "T1"
                    },
                    timestamp = System.currentTimeMillis() // Simplified
                )
                tradeDao.insert(trade)
            }
        } catch (e: Exception) { }
    }

    /**
     * FIX: connectWs now takes both host and port.
     * Previously it hardcoded :8000 which was wrong — backend runs on :8001.
     */
    fun connectWs(ip: String, port: String = _uiState.value.serverPort) {
        val cleanIp   = ip.removePrefix("http://").removePrefix("https://").substringBefore(":").trim()
        val cleanPort = port.ifBlank { PrefsRepository.DEFAULT_PORT }
        val wsUrl = "ws://$cleanIp:$cleanPort/ws/logs"

        if (_uiState.value.wsConnected && currentWsUrl == wsUrl) return

        wsManager?.disconnect()
        currentWsUrl = wsUrl
        wsManager = ArcWebSocketManager(
            client = okHttp,
            onMessage = { ts, msg -> addLog(ts, msg) },
            onConnected = { _uiState.update { it.copy(wsConnected = true) } },
            onDisconnected = { _uiState.update { it.copy(wsConnected = false) } }
        )
        wsManager?.connect(wsUrl)
    }

    private fun addLog(ts: String, msg: String) {
        viewModelScope.launch {
            val type = when {
                msg.contains("SNIPE") || msg.contains("🔥") -> LogType.SNIPE
                msg.contains("📈") -> LogType.PROFIT
                msg.contains("📉") -> LogType.LOSS
                msg.contains("💀") || msg.contains("rug") -> LogType.RUG
                msg.contains("REJECTED") || msg.contains("🚫") -> LogType.REJECTED
                msg.contains("PASS") || msg.contains("✅") -> LogType.PASS
                msg.contains("⚠️") || msg.contains("circuit") -> LogType.WARNING
                msg.contains("AI") || msg.contains("Gemini") || msg.contains("🤖") -> LogType.AI
                msg.contains("Tier-1") || msg.contains("📐") -> LogType.MATH
                msg.contains("Tier-2") || msg.contains("📊") -> LogType.MARKET
                msg.contains("Security") || msg.contains("🔒") -> LogType.SECURITY
                msg.contains("⏳") || msg.contains("Phase") -> LogType.WAITING
                else -> LogType.INFO
            }
            _logs.add(0, LogEntry(ts, msg, type))
            if (_logs.size > 500) _logs.removeLastOrNull()
            _uiState.update { it.copy(logs = _logs.toList()) }
        }
    }

    fun startEngine() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            try {
                val r = api.startEngine()
                addLog("SYS", "🚀 Engine ${r.status} | Mode: ${r.mode}")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMsg = "Backend unreachable: ${e.message}") }
            } finally { _uiState.update { it.copy(loading = false) } }
        }
    }

    fun stopEngine() {
        viewModelScope.launch {
            try {
                api.stopEngine()
                addLog("SYS", "🛑 Kill switch activated")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMsg = e.message) }
            }
        }
    }

    fun setSimulateMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSimulate(enabled)
            try { 
                val response = api.toggleSimulate(enabled)
                // Immediately update UI with the new mode and wallet balance from response
                _uiState.update { it.copy(
                    simulateMode = response.simulate,
                    stats = it.stats.copy(mode = response.mode)
                ) }
                fetchStats() // Refresh all stats immediately
            } catch (e: Exception) {
                addLog("SYS", "❌ Config sync failed: ${e.message}")
            }
            addLog("SYS", if (enabled) "🎮 SIMULATE MODE ON — paper trading" else "💰 LIVE MODE ON — real trades")
        }
    }

    fun setDarkMode(dark: Boolean) { viewModelScope.launch { prefs.setDarkMode(dark) } }

    /**
     * FIX: setServerIp now accepts and persists an optional port.
     * Parses "host:port" input correctly. Port defaults to 8001 if omitted.
     *
     * Also no longer strips port and hardcodes :8000 in the WS URL.
     */
    fun setServerIp(input: String) {
        val cleaned   = input.removePrefix("http://").removePrefix("https://").trim()
        val host      = cleaned.substringBefore(":").trim()
        val portStr   = cleaned.substringAfter(":", missingDelimiterValue = "")
                          .trim()
                          .ifBlank { PrefsRepository.DEFAULT_PORT }

        viewModelScope.launch {
            prefs.setServerIp(host)
            prefs.setServerPort(portStr)
            connectWs(host, portStr)
        }
    }

    fun clearError() { _uiState.update { it.copy(errorMsg = null) } }

    override fun onCleared() { wsManager?.disconnect() }
}
