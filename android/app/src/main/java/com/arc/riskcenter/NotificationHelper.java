package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core
 * Pranav · Kushal · Bharath B | Hacksagon 2026 · App Development Track
 *
 * NotificationHelper — Sends instant push notifications when the
 * WebSocket stream detects critical events (rug pull, snipe, exit).
 */

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotificationHelper {

    private static final String CHANNEL_ID   = "arc_alerts";
    private static final String CHANNEL_NAME = "ARC Risk Alerts";
    private static int notifId = 1000;

    public static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Real-time rug pull and snipe alerts from ARC engine");
            NotificationManager mgr = ctx.getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    public static void sendAlert(Context ctx, String title, String body) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(ctx).notify(notifId++, builder.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS permission not granted — silently ignore
        }
    }

    /** Parses a WebSocket log line and fires a notification if it's critical. */
    public static void checkAndNotify(Context ctx, String message) {
        if (message.contains("RUG") || message.contains("🚫 Security check failed")) {
            sendAlert(ctx, "⚠️  Rug Detected", message.length() > 80
                    ? message.substring(0, 80) + "…" : message);
        } else if (message.contains("🔥 SNIPE")) {
            sendAlert(ctx, "🔥 Snipe Executed", message.length() > 80
                    ? message.substring(0, 80) + "…" : message);
        } else if (message.contains("📉 EXIT")) {
            sendAlert(ctx, "📉 Position Closed", message.length() > 80
                    ? message.substring(0, 80) + "…" : message);
        }
    }
}
