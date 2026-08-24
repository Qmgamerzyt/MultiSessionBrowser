# ProGuard rules for Multi-Session Browser

# Keep Hilt generated code
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * extends dagger.hilt.android.HiltActivity { *; }
-keep class * extends dagger.hilt.android.HiltViewModel { *; }

# Keep Room database
-keep class com.app.browser.data.** { *; }

# Keep GeckoView
-keep class org.mozilla.geckoview.** { *; }

# Keep Kotlinx Serialization
-keep class kotlinx.serialization.** { *; }

# Keep Coil
-keep class io.coil-kt.** { *; }

# Keep OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Hilt generated components
-keep class * extends dagger.hilt.EntryPoint { *; }

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}