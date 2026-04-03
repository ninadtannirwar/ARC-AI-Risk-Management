package com.arc.riskcenter;

/**
 * ARC — Adaptive Risk Core
 * Pranav · Kushal · Bharath B | Hacksagon 2026 · App Development Track
 *
 * RetrofitClient — Singleton Retrofit instance with OkHttp logging.
 * CF-03 FIX: Base URL is accepted at runtime (from the IP dialog in
 *            MainActivity) instead of being hard-coded in BuildConfig.
 * SR-02 FIX: X-ARC-Key header is injected into every request.
 */

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.concurrent.TimeUnit;

public class RetrofitClient {

    private static RetrofitClient instance;
    private String baseUrl;
    private final ArcApiService apiService;

    private RetrofitClient(String baseUrl) {
        this.baseUrl = baseUrl;

        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                // SR-02 FIX: attach API key to every request
                .addInterceptor(chain -> chain.proceed(
                        chain.request().newBuilder()
                                .addHeader("X-ARC-Key", BuildConfig.ARC_API_KEY)
                                .build()
                ))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        apiService = retrofit.create(ArcApiService.class);
    }

    /**
     * CF-03 FIX: Re-creates the singleton if the base URL has changed
     * (e.g. user entered a new IP in the dialog).
     */
    public static RetrofitClient getInstance(String baseUrl) {
        if (instance == null || !instance.baseUrl.equals(baseUrl)) {
            instance = new RetrofitClient(baseUrl);
        }
        return instance;
    }

    /** Convenience overload — uses the last-configured URL. */
    public static RetrofitClient getInstance() {
        if (instance == null) {
            instance = new RetrofitClient(BuildConfig.BASE_URL);
        }
        return instance;
    }

    public ArcApiService getApi() {
        return apiService;
    }
}
