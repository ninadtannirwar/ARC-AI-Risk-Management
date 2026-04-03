package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core
 * Pranav · Kushal · Bharath B | Hacksagon 2026 · App Development Track
 *
 * MainActivity — Biometric-locked entry gate.
 * CF-03 FIX: Shows an IP input dialog before opening the dashboard so the
 *            app works on any venue WiFi without a rebuild.
 * CF-04 FIX: Removed "tap to continue" bypass — device credential (PIN)
 *            is now the fallback when biometrics aren't enrolled.
 */

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.arc.riskcenter.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private BiometricGuard biometricGuard;

    private static final String PREFS_NAME   = "arc_prefs";
    private static final String KEY_SERVER_IP = "server_ip";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnUnlock.setVisibility(View.INVISIBLE);

        biometricGuard = new BiometricGuard(
                this,
                new BiometricGuard.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        // CF-03 FIX: show IP dialog after auth, before opening dashboard
                        promptForServerIpIfNeeded();
                    }

                    @Override
                    public void onFailure(String message) {
                        binding.tvStatus.setText("Authentication failed: " + message);
                        // Show retry button — no bypass
                        binding.btnUnlock.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onError(String error) {
                        binding.tvStatus.setText("Biometric error: " + error);
                        binding.btnUnlock.setVisibility(View.VISIBLE);
                    }
                }
        );

        // CF-04 FIX: isAvailable() now checks BIOMETRIC_STRONG | DEVICE_CREDENTIAL,
        // so a device with a PIN but no enrolled fingerprint will still pass.
        if (biometricGuard.isAvailable()) {
            binding.tvStatus.setText("Authenticating…");
            biometricGuard.authenticate();
        } else {
            // No screen lock at all — inform user; don't silently skip the gate.
            binding.tvStatus.setText(
                "No screen lock detected.\nPlease set up a PIN or fingerprint in device settings."
            );
            // CF-04 FIX: removed tap-to-continue bypass entirely.
        }

        binding.btnUnlock.setOnClickListener(v -> biometricGuard.authenticate());
    }

    // ── CF-03: IP dialog ──────────────────────────────────────────

    private void promptForServerIpIfNeeded() {
        SharedPreferences prefs  = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String            saved  = prefs.getString(KEY_SERVER_IP, "");

        if (!saved.isEmpty()) {
            openDashboard(saved);
            return;
        }
        showIpDialog(prefs);
    }

    private void showIpDialog(SharedPreferences prefs) {
        EditText ipInput = new EditText(this);
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setHint("e.g. 192.168.1.42");

        // Add padding inside dialog
        LinearLayout container = new LinearLayout(this);
        container.setPadding(48, 16, 48, 0);
        container.addView(ipInput);

        new AlertDialog.Builder(this)
                .setTitle("Backend Server IP")
                .setMessage("Enter the IP address of the FastAPI server on this network:")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String ip = ipInput.getText().toString().trim();
                    if (ip.isEmpty()) {
                        ip = "localhost";
                    }
                    String baseUrl = "http://" + ip + ":8000";
                    String wsUrl   = "ws://"   + ip + ":8000/ws/logs";

                    prefs.edit()
                            .putString(KEY_SERVER_IP,  ip)
                            .putString("base_url",     baseUrl)
                            .putString("ws_url",       wsUrl)
                            .apply();

                    // Init Retrofit with the runtime URL before opening dashboard
                    RetrofitClient.getInstance(baseUrl);
                    openDashboard(ip);
                })
                .setNeutralButton("Reset saved IP", (dialog, which) -> {
                    prefs.edit().remove(KEY_SERVER_IP).apply();
                    showIpDialog(prefs);
                })
                .show();
    }

    private void openDashboard(String serverIp) {
        Intent intent = new Intent(this, RiskDashboardActivity.class);
        intent.putExtra("server_ip", serverIp);
        startActivity(intent);
        finish();
    }
}
