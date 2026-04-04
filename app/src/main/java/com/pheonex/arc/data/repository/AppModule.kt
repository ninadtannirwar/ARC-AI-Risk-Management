package com.pheonex.arc.data.repository

import android.content.Context
import androidx.room.Room
import com.google.gson.GsonBuilder
import com.google.gson.FieldNamingPolicy
import com.pheonex.arc.data.db.ArcDatabase
import com.pheonex.arc.data.db.ArcApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ArcDatabase =
        Room.databaseBuilder(ctx, ArcDatabase::class.java, "arc_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTradeDao(db: ArcDatabase) = db.tradeDao()
    @Provides fun providePortfolioDao(db: ArcDatabase) = db.portfolioDao()

    /**
     * FIX: OkHttp interceptor now reads BOTH host AND port from DataStore on every
     * request. This means the correct port (e.g. 8001) is always used regardless of
     * what the Retrofit baseUrl placeholder says.
     *
     * Previously the interceptor only swapped the host and kept the baseUrl port (8000),
     * causing every request to go to the wrong port.
     */
    @Provides @Singleton
    fun provideOkHttp(prefs: PrefsRepository): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .addInterceptor { chain ->
            val rawHost = runBlocking { prefs.serverIp.first() }.ifBlank { "localhost" }
            val port    = runBlocking { prefs.serverPort.first() }
                            .ifBlank { PrefsRepository.DEFAULT_PORT }
                            .toIntOrNull() ?: PrefsRepository.DEFAULT_PORT.toInt()

            // Sanitize: strip scheme and any embedded port from what the user typed
            val host = rawHost
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore(":")   // remove port if user included it in the IP field
                .trim()

            val newUrl = chain.request().url.newBuilder()
                .host(host)
                .port(port)
                .build()

            chain.proceed(
                chain.request().newBuilder()
                    .url(newUrl)
                    .addHeader("X-ARC-Key", "arc-pheonex-2026")
                    .build()
            )
        }
        .build()

    @Provides @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        // Placeholder only — host and port are replaced at call-time by the interceptor above
        .baseUrl("http://localhost:8001/")
        .client(client)
        .addConverterFactory(
            GsonConverterFactory.create(
                GsonBuilder()
                    .setLenient() // Added for stability with some server responses
                    .create()
            )
        )
        .build()

    @Provides @Singleton
    fun provideApiService(retrofit: Retrofit): ArcApiService =
        retrofit.create(ArcApiService::class.java)
}
