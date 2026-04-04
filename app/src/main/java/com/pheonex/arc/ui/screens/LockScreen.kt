package com.pheonex.arc.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pheonex.arc.ui.theme.*

@Composable
fun LockScreen(onAuthenticated: () -> Unit) {
    val ctx = LocalContext.current
    var statusMsg by remember { mutableStateOf("INITIALIZING SECURE PROTOCOL…") }
    var showRetry by remember { mutableStateOf(false) }

    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "pulse"
    )

    fun authenticate() {
        val executor = ContextCompat.getMainExecutor(ctx)
        val activity = ctx as FragmentActivity
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onAuthenticated()
            }
            override fun onAuthenticationFailed() {
                statusMsg = "AUTHENTICATION FAILED"
                showRetry = true
            }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                statusMsg = if (code == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                    code == BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL) {
                    if (com.pheonex.arc.BuildConfig.DEBUG) {
                        onAuthenticated()
                        return
                    } else "SECURITY ERROR: BIOMETRICS REQUIRED"
                } else "ERROR: $msg"
                showRetry = true
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ARC SECURE ACCESS")
            .setSubtitle("NEURAL UPLINK VERIFICATION REQUIRED")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            ).build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) { authenticate() }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0A0B10), Color(0xFF000000)))
        ),
        contentAlignment = Alignment.Center
    ) {
        // Background Glow
        Box(Modifier.size(300.dp).blur(100.dp).background(ArcBlue.copy(0.05f), CircleShape))
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // High-tech logo
            Box(contentAlignment = Alignment.Center) {
                // Outer ring
                Box(Modifier.size(160.dp).border(2.dp, ArcBlue.copy(0.1f), CircleShape))
                // Middle pulsing ring
                Box(Modifier.size(130.dp * pulse).border(1.dp, ArcBlue.copy(0.3f), CircleShape))
                
                // Central Hex
                Box(
                    Modifier.size(100.dp).background(ArcSurface, CircleShape)
                        .border(1.dp, ArcBlue.copy(0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⬡", fontSize = 64.sp, color = ArcBlue, 
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            Spacer(Modifier.height(40.dp))
            
            Text("ARC", fontSize = 52.sp, fontWeight = FontWeight.Black,
                color = Color.White, letterSpacing = 8.sp, fontFamily = FontFamily.Monospace)
            Text("ADAPTIVE RISK CORE", fontSize = 10.sp, color = ArcBlue,
                letterSpacing = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            
            Spacer(Modifier.height(8.dp))
            Text("v2.0 // TEAM PHEONEX", fontSize = 11.sp, color = ArcGreen.copy(0.7f), 
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

            Spacer(Modifier.height(60.dp))

            Surface(
                color = ArcSurfaceVar.copy(0.4f), 
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ArcBlue.copy(0.2f))
            ) {
                Row(Modifier.padding(20.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if(showRetry) ArcRed else ArcGreen, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text(statusMsg, color = Color.White.copy(0.8f), 
                        fontSize = 11.sp, letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(32.dp))
            
            AnimatedVisibility(
                visible = showRetry,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Button(
                    onClick = { showRetry = false; authenticate() },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp).padding(horizontal = 32.dp)
                ) {
                    Icon(Icons.Default.Fingerprint, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("RETRY AUTH", color = Color.Black, fontWeight = FontWeight.Black)
                }
            }
        }
        
        // Footer security notice
        Text("ENCRYPTION: AES-256-GCM  //  SECURE ENCLAVE ACTIVE", 
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            fontSize = 8.sp, color = Color.White.copy(0.2f),
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
    }
}
