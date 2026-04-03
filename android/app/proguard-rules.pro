# ARC — Adaptive Risk Core
# Owner: Bharath B

# Keep Retrofit interfaces
-keep interface com.arc.riskcenter.ArcApiService { *; }

# Keep response model classes (Gson needs field names)
-keepclassmembers class com.arc.riskcenter.ArcApiService$* {
    <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
