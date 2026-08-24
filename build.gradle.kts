buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        val agpVersion = "8.5.0"
        val kotlinVersion = "2.0.0"
        val safeargsVersion = "2.7.7"
        val serializationVersion = "1.6.3"
        classpath("com.android.tools.build:gradle:$agpVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:$safeargsVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$serializationVersion")
    }
}