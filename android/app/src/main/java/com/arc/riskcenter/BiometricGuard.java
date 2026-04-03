package com.arc.riskcenter;

/**
 * BiometricGuard — Wraps AndroidX BiometricPrompt.
 * Provides fingerprint / FaceID authentication before granting
 * access to the risk dashboard and wallet operations.
 */

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class BiometricGuard {

    public interface AuthCallback {
        void onSuccess();
        void onFailure(String message);
        void onError(String error);
    }

    private final FragmentActivity activity;
    private final AuthCallback callback;
    private final BiometricPrompt biometricPrompt;
    private final BiometricPrompt.PromptInfo promptInfo;

    public BiometricGuard(FragmentActivity activity, AuthCallback callback) {
        this.activity = activity;
        this.callback = callback;

        Executor executor = ContextCompat.getMainExecutor(activity);

        biometricPrompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        callback.onFailure("Biometric not recognised. Please try again.");
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        callback.onError(errString.toString());
                    }
                });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("ARC — Secure Access")
                .setSubtitle("Authenticate to access the Risk Engine")
                .setDescription("Biometric-Locked")
                // CF-04 FIX: allow PIN/pattern fallback so the demo doesn't freeze
                // after 3 failed fingerprint attempts. NOTE: setNegativeButtonText()
                // must NOT be set when DEVICE_CREDENTIAL is in the allowed set.
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG |
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build();
    }

    /** Returns true if the device can perform biometric or device-credential authentication. */
    public boolean isAvailable() {
        BiometricManager manager = BiometricManager.from(activity);
        // CF-04 FIX: check the full set we allow — BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        int result = manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG |
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        );
        return result == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** Trigger the biometric prompt. */
    public void authenticate() {
        biometricPrompt.authenticate(promptInfo);
    }
}
