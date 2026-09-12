# R8 / ProGuard rules (only used when minify is enabled in app/build.gradle.kts)
-keepattributes *Annotation*
-keep class app.multisession.browser.data.db.** { *; }
-dontwarn org.jetbrains.annotations.**
# GeckoView ships its own consumer rules inside the AAR; these are belt-and-braces for JNI entry points.
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.geckoview.**
