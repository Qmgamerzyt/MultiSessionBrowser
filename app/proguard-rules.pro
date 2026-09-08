# R8 / ProGuard rules (only used when minify is enabled in app/build.gradle.kts)
-keepattributes *Annotation*
-keep class app.multisession.browser.data.db.** { *; }
-dontwarn org.jetbrains.annotations.**
