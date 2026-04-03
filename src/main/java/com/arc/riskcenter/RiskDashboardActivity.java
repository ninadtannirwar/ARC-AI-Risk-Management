package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core  ·  Singularity Engine v3.0
 * 🏆 Hacksagon 2026  ·  App Development Track
 * 👑 Pranav · Kushal · Bharath B
 *
 * RiskDashboardActivity — Command centre UI.
 *
 * Features:
 *   • Three-Tier Pipeline display (T1 Math | T2 Market | T3 AI scores)
 *   • AI Confidence Meter (ProgressBar driven by real Gemini scores)
 *   • Risk Level Badge (LOW / MEDIUM / HIGH CONFIDENCE)
 *   • Live colour-coded scrolling log (WebSocket from FastAPI)
 *   • Stats panel: Open/Closed trades, Win Rate, Daily P/L, AI calls
 *   • Kill Switch button with confirmation
 *   • Auto-polling stats every 10 seconds
 */

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.arc.riskcenter.databinding.ActivityDashboardBinding;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class RiskDashboardActivity extends AppCompatActivity
        implements ArcWebSocketClient.LogListener {

    private ActivityDashboardBinding binding;
    private ArcWebSocketClient wsClient;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    private static final int STATS_POLL_MS = 10_000;

    // ── Lifecycle ──────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDashboardBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        String serverIp = getIntent().getStringExtra("server_ip");
        if (serverIp != null && !serverIp.isEmpty()) {
            String baseUrl = "http://" + serverIp + ":8000";
            RetrofitClient.getInstance(baseUrl);
        }

        setupButtons();
        setupWebSocket();
        pollStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacksAndMessages(null);
        if (wsClient != null) wsClient.disconnect();
    }

    // ── Button wiring ──────────────────────────────────────────────

    private void setupButtons() {
        binding.btnStartEngine.setOnClickListener(v -> startEngine());
        binding.btnStopEngine.setOnClickListener(v -> confirmKillSwitch());
    }

    private void startEngine() {
        binding.btnStartEngine.setEnabled(false);
        RetrofitClient.getInstance().getApi().startEngine()
                .enqueue(new Callback<ArcApiService.EngineResponse>() {
                    @Override
                    public void onResponse(Call<ArcApiService.EngineResponse> call,
                                           Response<ArcApiService.EngineResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            ArcApiService.EngineResponse body = response.body();
                            appendLog("🚀", "Engine " + body.status + " | Mode: " + body.mode);
                            if (body.tiers != null)
                                appendLog("📐", "Pipeline: " + body.tiers);
                            binding.btnStopEngine.setEnabled(true);
                        }
                        binding.btnStartEngine.setEnabled(true);
                    }

                    @Override
                    public void onFailure(Call<ArcApiService.EngineResponse> call, Throwable t) {
                        Toast.makeText(RiskDashboardActivity.this,
                                "Cannot reach backend: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                        binding.btnStartEngine.setEnabled(true);
                    }
                });
    }

    /** Show confirmation dialog before activating kill switch. */
    private void confirmKillSwitch() {
        new AlertDialog.Builder(this)
                .setTitle("🛑 Activate Kill Switch?")
                .setMessage("This will close ALL open positions immediately and stop the engine.\n\nAre you sure?")
                .setPositiveButton("Kill Switch", (d, w) -> stopEngine())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopEngine() {
        binding.btnStopEngine.setEnabled(false);
        RetrofitClient.getInstance().getApi().stopEngine()
                .enqueue(new Callback<ArcApiService.EngineResponse>() {
                    @Override
                    public void onResponse(Call<ArcApiService.EngineResponse> call,
                                           Response<ArcApiService.EngineResponse> response) {
                        if (response.isSuccessful()) {
                            appendLog("🛑", "Kill switch activated — closing all positions…");
                        }
                        binding.btnStartEngine.setEnabled(true);
                    }

                    @Override
                    public void onFailure(Call<ArcApiService.EngineResponse> call, Throwable t) {
                        binding.btnStopEngine.setEnabled(true);
                    }
                });
    }

    // ── WebSocket ──────────────────────────────────────────────────

    private void setupWebSocket() {
        wsClient = new ArcWebSocketClient(this, this);
        wsClient.connect();
    }

    @Override
    public void onConnected() {
        binding.tvConnectionStatus.setText("● Connected");
        binding.tvConnectionStatus.setTextColor(Color.parseColor("#4CAF50"));
        appendLog("📡", "Live stream connected to ARC Singularity v3.0");
    }

    @Override
    public void onDisconnected(String reason) {
        binding.tvConnectionStatus.setText("○ Disconnected — reconnecting…");
        binding.tvConnectionStatus.setTextColor(Color.parseColor("#FF5722"));
    }

    @Override
    public void onMessage(String timestamp, String message) {
        appendLog(timestamp, message);
        updateConfidenceMeterFromLog(message);
    }

    // ── Live log view (colour-coded by event type) ─────────────────

    private void appendLog(String prefix, String message) {
        int colour = Color.parseColor("#C9D1D9");   // default: muted white

        if (message.contains("SNIPE") || message.contains("🔥"))
            colour = Color.parseColor("#4CAF50");   // green — buy executed
        else if (message.contains("📈"))
            colour = Color.parseColor("#69F0AE");   // bright green — profit exit
        else if (message.contains("📉"))
            colour = Color.parseColor("#FF5252");   // red — loss exit
        else if (message.contains("💀") || message.contains("rugged"))
            colour = Color.parseColor("#F44336");   // red — rug detected
        else if (message.contains("🚫") || message.contains("REJECTED") || message.contains("rejected"))
            colour = Color.parseColor("#FF7043");   // deep orange — rejected
        else if (message.contains("✅") || message.contains("PASS") || message.contains("pass"))
            colour = Color.parseColor("#81C784");   // light green — tier pass
        else if (message.contains("⚠️") || message.contains("circuit") || message.contains("CIRCUIT"))
            colour = Color.parseColor("#FF9800");   // orange — warning
        else if (message.contains("🤖") || message.contains("Tier-3") || message.contains("AI") || message.contains("Gemini"))
            colour = Color.parseColor("#64B5F6");   // blue — AI events
        else if (message.contains("📐") || message.contains("Tier-1"))
            colour = Color.parseColor("#CE93D8");   // purple — math tier
        else if (message.contains("📊") || message.contains("Tier-2"))
            colour = Color.parseColor("#80DEEA");   // cyan — market tier
        else if (message.contains("🔒") || message.contains("Security"))
            colour = Color.parseColor("#FFD54F");   // amber — security
        else if (message.contains("⏳") || message.contains("Phase"))
            colour = Color.parseColor("#8B949E");   // grey — waiting

        String line = "[" + prefix + "] " + message + "\n";
        SpannableString span = new SpannableString(line);
        span.setSpan(new ForegroundColorSpan(colour), 0, line.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        binding.tvLogs.append(span);
        binding.scrollLogs.post(() -> binding.scrollLogs.fullScroll(ScrollView.FOCUS_DOWN));

        // Keep log bounded at ~300 lines
        if (binding.tvLogs.getLineCount() > 300) {
            String text = binding.tvLogs.getText().toString();
            int cut = text.indexOf('\n', text.length() / 3);
            if (cut > 0) binding.tvLogs.setText(text.substring(cut + 1));
        }
    }

    // ── AI Confidence Meter heuristic from log events ──────────────

    private void updateConfidenceMeterFromLog(String message) {
        if (message.contains("SNIPE") || message.contains("🔥")) {
            setConfidenceMeter(90, "HIGH CONFIDENCE");
        } else if (message.contains("Tier-3") && message.contains("APPROVED")) {
            setConfidenceMeter(87, "HIGH CONFIDENCE");
        } else if (message.contains("Tier-3") && message.contains("REJECTED")) {
            setConfidenceMeter(40, "BELOW THRESHOLD");
        } else if (message.contains("Tier-1") && message.contains("pass")) {
            setConfidenceMeter(55, "T1 PASSED");
        } else if (message.contains("Tier-2") && message.contains("pass")) {
            setConfidenceMeter(70, "T2 PASSED");
        } else if (message.contains("💀")) {
            setConfidenceMeter(10, "RUG DETECTED");
        }
    }

    private void setConfidenceMeter(int value, String label) {
        binding.progressTrust.setProgress(value);
        binding.tvTrustValue.setText(value + "%");
        binding.tvRiskLevel.setText(label);
        if (value >= 85) {
            binding.tvRiskLevel.setTextColor(Color.parseColor("#4CAF50"));
            binding.progressTrust.getProgressDrawable()
                    .setColorFilter(Color.parseColor("#4CAF50"),
                            android.graphics.PorterDuff.Mode.SRC_IN);
        } else if (value >= 50) {
            binding.tvRiskLevel.setTextColor(Color.parseColor("#FF9800"));
            binding.progressTrust.getProgressDrawable()
                    .setColorFilter(Color.parseColor("#FF9800"),
                            android.graphics.PorterDuff.Mode.SRC_IN);
        } else {
            binding.tvRiskLevel.setTextColor(Color.parseColor("#F44336"));
            binding.progressTrust.getProgressDrawable()
                    .setColorFilter(Color.parseColor("#F44336"),
                            android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    // ── Stats polling (every 10 s) ─────────────────────────────────

    private void pollStats() {
        RetrofitClient.getInstance().getApi().getStats()
                .enqueue(new Callback<ArcApiService.StatsResponse>() {
                    @Override
                    public void onResponse(Call<ArcApiService.StatsResponse> call,
                                           Response<ArcApiService.StatsResponse> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            updateStatsUI(response.body());
                        }
                    }

                    @Override
                    public void onFailure(Call<ArcApiService.StatsResponse> call, Throwable t) {
                        binding.tvEngineStatus.setText("Backend unreachable — check IP");
                        binding.tvEngineStatus.setTextColor(Color.parseColor("#FF5722"));
                    }
                });

        pollHandler.postDelayed(this::pollStats, STATS_POLL_MS);
    }

    private void updateStatsUI(ArcApiService.StatsResponse s) {
        // Engine status
        binding.tvEngineStatus.setText(s.engineRunning
                ? "● ENGINE RUNNING  [" + s.mode + "]"
                : "○ Engine stopped");
        binding.tvEngineStatus.setTextColor(s.engineRunning
                ? Color.parseColor("#4CAF50") : Color.GRAY);

        // Trade stats
        binding.tvTradesOpen.setText(String.valueOf(s.tradesOpen));
        binding.tvTradesClosed.setText(String.valueOf(s.tradesClosed));
        binding.tvWinRate.setText(String.format("%.1f%%", s.winRate));
        binding.tvAvgProfit.setText(String.format("%+.2f%%", s.avgProfitPct));
        binding.tvAiCalls.setText(s.aiCallsToday + " / " + s.aiCallsLimit);

        // Daily P/L colouring
        if (s.dailyPnlSol >= 0) {
            binding.tvDailyPnl.setText(String.format("+%.4f SOL", s.dailyPnlSol));
            binding.tvDailyPnl.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            binding.tvDailyPnl.setText(String.format("%.4f SOL", s.dailyPnlSol));
            binding.tvDailyPnl.setTextColor(Color.parseColor("#F44336"));
        }

        // Three-Tier Pipeline scores display
        if (s.avgT1Score > 0) {
            binding.tvT1Score.setText(String.format("%.2f", s.avgT1Score));
            colorScoreText(binding.tvT1Score, s.avgT1Score, 0.30);
        }
        if (s.avgT2Score > 0) {
            binding.tvT2Score.setText(String.format("%.2f", s.avgT2Score));
            colorScoreText(binding.tvT2Score, s.avgT2Score, 0.40);
        }
        if (s.avgAiConfidence > 0) {
            binding.tvT3Score.setText(String.format("%.2f", s.avgAiConfidence));
            colorScoreText(binding.tvT3Score, s.avgAiConfidence, 0.85);
            // Drive confidence meter from real Gemini scores
            setConfidenceMeter(
                    (int)(s.avgAiConfidence * 100),
                    s.avgAiConfidence >= 0.85 ? "HIGH CONFIDENCE"
                    : s.avgAiConfidence >= 0.60 ? "MODERATE"
                    : "BELOW THRESHOLD"
            );
        }
    }

    /** Colour a score TextView green/orange/red vs its gate threshold. */
    private void colorScoreText(android.widget.TextView tv, double score, double gate) {
        int colour;
        if (score >= gate * 1.2) colour = Color.parseColor("#4CAF50");
        else if (score >= gate)  colour = Color.parseColor("#81C784");
        else                     colour = Color.parseColor("#FF9800");
        tv.setTextColor(colour);
    }
}
