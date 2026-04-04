package com.pheonex.arc.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("arc_prefs")

@Singleton
class PrefsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context
) {
    companion object {
        val KEY_SERVER_IP   = stringPreferencesKey("server_ip")
        val KEY_SERVER_PORT = stringPreferencesKey("server_port")
        val KEY_SIMULATE    = booleanPreferencesKey("simulate_mode")
        val KEY_DARK_MODE   = booleanPreferencesKey("dark_mode")
        val KEY_WALLET_SOL  = doublePreferencesKey("wallet_sol")

        const val DEFAULT_PORT = "8001"
    }

    /** Stored as raw IP only (no port, no scheme). e.g. "10.244.49.236" */
    val serverIp: Flow<String>      = ctx.dataStore.data.map { it[KEY_SERVER_IP]   ?: "" }
    /** Stored as port string. e.g. "8001" */
    val serverPort: Flow<String>    = ctx.dataStore.data.map { it[KEY_SERVER_PORT] ?: DEFAULT_PORT }
    val simulateMode: Flow<Boolean> = ctx.dataStore.data.map { it[KEY_SIMULATE]    ?: false }
    val darkMode: Flow<Boolean>     = ctx.dataStore.data.map { it[KEY_DARK_MODE]   ?: true }
    val walletSol: Flow<Double>     = ctx.dataStore.data.map { it[KEY_WALLET_SOL]  ?: 0.0 }

    suspend fun setServerIp(ip: String)        { ctx.dataStore.edit { it[KEY_SERVER_IP]   = ip } }
    suspend fun setServerPort(port: String)    { ctx.dataStore.edit { it[KEY_SERVER_PORT] = port } }
    suspend fun setSimulate(enabled: Boolean)  { ctx.dataStore.edit { it[KEY_SIMULATE]    = enabled } }
    suspend fun setDarkMode(dark: Boolean)     { ctx.dataStore.edit { it[KEY_DARK_MODE]   = dark } }
    suspend fun setWalletSol(sol: Double)      { ctx.dataStore.edit { it[KEY_WALLET_SOL]  = sol } }
}
