package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core  ·  Singularity Engine v3.0
 * 🏆 Hacksagon 2026  ·  App Development Track
 * 👑 Pranav · Kushal · Bharath B
 *
 * ArcApiService — Retrofit interface.
 * Maps to FastAPI endpoints: /start, /stop, /stats, /health
 */

import com.google.gson.annotations.SerializedName;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.POST;

public interface ArcApiService {

    @POST("/start")
    Call<EngineResponse> startEngine();

    @POST("/stop")
    Call<EngineResponse> stopEngine();

    @GET("/stats")
    Call<StatsResponse> getStats();

    @GET("/health")
    Call<HealthResponse> health();

    class EngineResponse {
        @SerializedName("status")  public String status;
        @SerializedName("mode")    public String mode;
        @SerializedName("message") public String message;
        @SerializedName("engine")  public String engine;
        @SerializedName("tiers")   public String tiers;
    }

    class StatsResponse {
        @SerializedName("engine_running")     public boolean engineRunning;
        @SerializedName("mode")               public String  mode;
        @SerializedName("started_at")         public String  startedAt;
        @SerializedName("trades_open")        public int     tradesOpen;
        @SerializedName("trades_closed")      public int     tradesClosed;
        @SerializedName("win_rate")           public double  winRate;
        @SerializedName("avg_profit_pct")     public double  avgProfitPct;
        @SerializedName("daily_pnl_sol")      public double  dailyPnlSol;
        @SerializedName("ai_calls_today")     public int     aiCallsToday;
        @SerializedName("ai_calls_limit")     public int     aiCallsLimit;
        @SerializedName("tokens_scanned")     public int     tokensScanned;
        @SerializedName("avg_t1_score")       public double  avgT1Score;
        @SerializedName("avg_t2_score")       public double  avgT2Score;
        @SerializedName("avg_ai_confidence")  public double  avgAiConfidence;
    }

    class HealthResponse {
        @SerializedName("status")         public String  status;
        @SerializedName("engine")         public String  engine;
        @SerializedName("version")        public String  version;
        @SerializedName("engine_running") public boolean engineRunning;
        @SerializedName("project")        public String  project;
        @SerializedName("owners")         public String  owners;
    }
}
