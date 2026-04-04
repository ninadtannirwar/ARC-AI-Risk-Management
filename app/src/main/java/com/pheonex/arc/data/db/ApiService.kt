package com.pheonex.arc.data.db

import com.google.gson.annotations.SerializedName
import okhttp3.*
import org.json.JSONObject
import retrofit2.http.*

// ── Response models ────────────────────────────────────────────────────────

data class TradeResponse(
    @SerializedName("status")           val status: String,
    @SerializedName("trade_id")         val tradeId: String,
    @SerializedName("symbol")           val symbol: String,
    @SerializedName("mint")             val mint: String,
    @SerializedName("entry_mc")         val entryMc: Double?,
    @SerializedName("ath_mc")           val athMc: Double?,
    @SerializedName("investment_sol")   val investmentSol: Double?,
    @SerializedName("entry_time")       val entryTime: String?,
    @SerializedName("t1_score")         val t1Score: Double?,
    @SerializedName("t2_score")         val t2Score: Double?,
    @SerializedName("ai_confidence")    val aiConfidence: Double?,
    @SerializedName("regime")           val regime: String?,
    @SerializedName("exit_mc")          val exitMc: Double?,
    @SerializedName("exit_reason")      val exitReason: String?,
    @SerializedName("hold_minutes")     val holdMinutes: Double?,
    @SerializedName("gross_pnl_sol")    val grossPnlSol: Double?,
    @SerializedName("net_pnl_sol")      val netPnlSol: Double?,
    @SerializedName("timestamp")        val timestamp: String?
)

data class EngineResponse(
    val status: String = "",
    val mode: String = "",
    val tiers: String? = null
)

/**
 * CHANGE 1: Added @SerializedName to every field whose Gson-default name
 * (camelCase) differs from the backend's snake_case JSON keys.
 *
 * Without these annotations Gson silently leaves every field at its default
 * value (0 / false / "LIVE") because it cannot match e.g. "engine_running"
 * → engineRunning. The entire stats card would show zeroes.
 *
 * CHANGE 2: Added walletSol / wallet_sol — now returned by the backend
 * stats_store so Android can display the current SOL balance.
 */
data class StatsResponse(
    @SerializedName("engine_running")           val engineRunning: Boolean = false,
    @SerializedName("mode")                     val mode: String = "LIVE",
    @SerializedName("trades_open")              val tradesOpen: Int = 0,
    @SerializedName("trades_closed")            val tradesClosed: Int = 0,
    @SerializedName("win_rate")                 val winRate: Double = 0.0,
    @SerializedName("avg_profit_pct")           val avgProfitPct: Double = 0.0,
    @SerializedName("daily_pnl_sol")            val dailyPnlSol: Double = 0.0,
    @SerializedName("ai_calls_today")           val aiCallsToday: Int = 0,
    @SerializedName("ai_calls_limit")           val aiCallsLimit: Int = 20,
    @SerializedName("avg_t1_score")             val avgT1Score: Double = 0.0,
    @SerializedName("avg_t2_score")             val avgT2Score: Double = 0.0,
    @SerializedName("avg_ai_confidence")        val avgAiConfidence: Double = 0.0,
    @SerializedName("wallet_sol")               val walletSol: Double = 0.0,
    // Simulate-mode profitable-trades progress
    @SerializedName("profitable_trades")        val profitableTrades: Int = 0,
    @SerializedName("target_profitable_trades") val targetProfitableTrades: Int = 5,
)

/**
 * CHANGE 3: SimulateToggleResponse now includes `mode` to match the new
 * /config endpoint response body: {"simulate": bool, "mode": str, "message": str}
 */
data class SimulateToggleResponse(
    val simulate: Boolean = false,
    val mode: String = "",
    val message: String = ""
)

// ── Retrofit interface ─────────────────────────────────────────────────────

/**
 * CHANGE 4: Fixed endpoint paths.
 *   engine/start → start    (backend exposes POST /start)
 *   engine/stop  → stop     (backend exposes POST /stop)
 *   engine/simulate → config (backend now exposes POST /config)
 *
 * The old "engine/ " wildcard prefix matched nothing — every call threw a 404
 * which was swallowed by the try/catch, so the UI appeared to work
 * while doing absolutely nothing.
 */
interface ArcApiService {
    @POST("start")
    suspend fun startEngine(): EngineResponse

    @POST("stop")
    suspend fun stopEngine(): EngineResponse

    @GET("stats")
    suspend fun getStats(): StatsResponse

    @GET("trades")
    suspend fun getTrades(): List<TradeResponse>

    @POST("config")
    suspend fun toggleSimulate(@Query("simulate") simulate: Boolean): SimulateToggleResponse
}

// ── WebSocket manager ──────────────────────────────────────────────────────

/**
 * CHANGE 5: Fixed WebSocket message parsing.
 *
 * Backend broadcasts JSON:  {"ts": "HH:MM:SS", "msg": "…"}
 * Old code did:             text.split("|", limit = 2)
 *   → always produced a single-element list because "|" is not present,
 *     so ts was always "NOW" and msg was the raw JSON string displayed
 *     verbatim in the logbook.
 *
 * Fix: parse the JSON object and extract the "ts" and "msg" fields.
 * Graceful fallback to ("NOW", rawText) if parsing fails (keeps the
 * old behaviour for any non-JSON messages the engine might emit).
 */
class ArcWebSocketManager(
    private val client: OkHttpClient,
    private val onMessage: (String, String) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    private var ws: WebSocket? = null

    fun connect(wsUrl: String) {
        val req = Request.Builder().url(wsUrl).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onConnected()

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val ts  = json.optString("ts", "NOW")
                    val msg = json.optString("msg", text)
                    onMessage(ts, msg)
                } catch (_: Exception) {
                    // Non-JSON frame — pass raw text through unchanged
                    onMessage("NOW", text)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                onDisconnected(reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onDisconnected(t.message ?: "Connection failed")
        })
    }

    fun disconnect() { ws?.close(1000, "User closed"); ws = null }
}
