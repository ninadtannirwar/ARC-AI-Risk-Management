package com.pheonex.arc.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pheonex.arc.data.repository.PrefsRepository
import com.pheonex.arc.ui.theme.*
import com.pheonex.arc.viewmodel.DashboardUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: DashboardUiState,
    onSimulateToggle: (Boolean) -> Unit,
    onDarkModeToggle: (Boolean) -> Unit,
    onSetIp: (String) -> Unit,
    onSeedDemo: () -> Unit
) {
    // editIp stores "host:port" combined for the input field
    val combinedDefault = remember(state.serverIp, state.serverPort) {
        if (state.serverIp.isBlank()) ""
        else if (state.serverPort.isBlank() || state.serverPort == PrefsRepository.DEFAULT_PORT)
            "${state.serverIp}:${PrefsRepository.DEFAULT_PORT}"
        else "${state.serverIp}:${state.serverPort}"
    }
    var editIp by remember(combinedDefault) { mutableStateOf(combinedDefault) }
    var showSeedConfirm by remember { mutableStateOf(false) }

    if (showSeedConfirm) {
        AlertDialog(
            onDismissRequest = { showSeedConfirm = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Seed Demo Data?", fontWeight = FontWeight.Bold) },
            text = { Text("This will add sample trades to your history for demonstration purposes.") },
            confirmButton = {
                Button(onClick = { onSeedDemo(); showSeedConfirm = false }) {
                    Text("Add Data")
                }
            },
            dismissButton = { TextButton(onClick = { showSeedConfirm = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Profile Summary (Mock)
            ProfileHeader()

            // Trading Settings
            SettingsSection("Trading Preferences") {
                Column {
                    ModernToggleRow(
                        icon = Icons.Outlined.Science,
                        title = "Practice Mode",
                        subtitle = "Trade with virtual SOL balance",
                        checked = state.simulateMode,
                        onToggle = onSimulateToggle
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(0.1f))
                    ModernToggleRow(
                        icon = if (state.darkMode) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                        title = "Dark Theme",
                        subtitle = "Switch between light and dark UI",
                        checked = state.darkMode,
                        onToggle = onDarkModeToggle
                    )
                }
            }

            // Connection Settings
            SettingsSection("Network & Connectivity") {
                Column(Modifier.padding(16.dp)) {
                    // Status row
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(36.dp),
                            shape = CircleShape,
                            color = (if (state.wsConnected) GrowwGreen else GrowwRed).copy(0.1f)
                        ) {
                            Icon(
                                if (state.wsConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (state.wsConnected) GrowwGreen else GrowwRed,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Connection Status", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (state.wsConnected) "Stable Connection" else "Disconnected from server",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.wsConnected) GrowwGreen else GrowwRed
                            )
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Combined host:port input field
                    OutlinedTextField(
                        value = editIp,
                        onValueChange = { editIp = it },
                        label = { Text("Server Address (host:port)") },
                        placeholder = { Text("e.g. 10.244.49.236:8001") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = {
                            Text(
                                "Enter your laptop's WiFi IP and port. Default port: ${PrefsRepository.DEFAULT_PORT}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { onSetIp(editIp) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Update Connection", fontWeight = FontWeight.Bold)
                    }

                    // Quick hint if disconnected
                    if (!state.wsConnected && state.serverIp.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠️ Tip: Make sure your phone and laptop are on the same WiFi network. " +
                            "Check Windows Firewall allows port ${state.serverPort.ifBlank { PrefsRepository.DEFAULT_PORT }}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // App Info
            SettingsSection("App Information") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    InfoRowItem("Version", "2.1.0-stable")
                    InfoRowItem("Network", "Solana Mainnet")
                    InfoRowItem("AI Engine", "ARC v2 Neural")
                    InfoRowItem(
                        "Backend",
                        if (state.serverIp.isBlank()) "Not configured"
                        else "${state.serverIp}:${state.serverPort.ifBlank { PrefsRepository.DEFAULT_PORT }}"
                    )
                }
            }

            // Debug Tools
            TextButton(
                onClick = { showSeedConfirm = true },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Seed Demo History", color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ProfileHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(60.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(0.1f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("JD", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Trader Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Verified User", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(0.05f))
        ) {
            content()
        }
    }
}

@Composable
fun ModernToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
fun InfoRowItem(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}
