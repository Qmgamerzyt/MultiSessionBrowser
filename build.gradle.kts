buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        val agpVersion = "8.5.0"
        val kotlinVersion = "2.0.0"
        classpath("com.android.tools.build:gradle:$agpVersion")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}