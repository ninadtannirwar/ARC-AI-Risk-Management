package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core
 * Pranav · Kushal · Bharath B | Hacksagon 2026 · App Development Track
 *
 * ArcWebSocketClient — OkHttp WebSocket connecting to /ws/logs.
 * CF-03 FIX: WS URL is derived from the runtime server IP stored in
 *            SharedPreferences, not the hardcoded BuildConfig.WS_URL.
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.util.concurrent.TimeUnit;

public class ArcWebSocketClient {

    public interface LogListener {
        void onMessage(String timestamp, String message);
        void onConnected();
        void onDisconnected(String reason);
    }

    private final LogListener listener;
    private final Context context;
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebSocket webSocket;
    private boolean shouldReconnect = true;

    public ArcWebSocketClient(LogListener listener, Context context) {
        this.listener = listener;
        this.context  = context.getApplicationContext();
        this.client   = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)  // No timeout on WS
                .build();
    }

    /** CF-03 FIX: derive WS URL from saved server IP, fallback to BuildConfig. */
    private String resolveWsUrl() {
        SharedPreferences prefs = context.getSharedPreferences("arc_prefs", Context.MODE_PRIVATE);
        String ip = prefs.getString("server_ip", "");
        if (!ip.isEmpty()) {
            return "ws://" + ip + ":8000/ws/logs";
        }
        return BuildConfig.WS_URL;
    }

    public void connect() {
        shouldReconnect = true;
        Request request = new Request.Builder()
                .url(resolveWsUrl())
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                mainHandler.post(() -> listener.onConnected());
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonObject obj = gson.fromJson(text, JsonObject.class);
                    String ts  = obj.has("ts")  ? obj.get("ts").getAsString()  : "--:--:--";
                    String msg = obj.has("msg") ? obj.get("msg").getAsString() : text;
                    mainHandler.post(() -> listener.onMessage(ts, msg));
                } catch (Exception e) {
                    mainHandler.post(() -> listener.onMessage("--", text));
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                String reason = t.getMessage() != null ? t.getMessage() : "Connection failed";
                mainHandler.post(() -> listener.onDisconnected(reason));

                // Auto-reconnect after 3 seconds
                if (shouldReconnect) {
                    mainHandler.postDelayed(ArcWebSocketClient.this::connect, 3000);
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                mainHandler.post(() -> listener.onDisconnected(reason));
            }
        });
    }

    public void disconnect() {
        shouldReconnect = false;
        if (webSocket != null) {
            webSocket.close(1000, "User disconnected");
        }
    }
}
