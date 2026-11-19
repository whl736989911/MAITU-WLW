pluginManagement {
    val local = java.util.Properties()
    file("local.properties").inputStream().use(local::load)
    val sdk = requireNotNull(local.getProperty("flutter.sdk")) { "flutter.sdk missing from local.properties" }
    includeBuild("$sdk/packages/flutter_tools/gradle")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.3.3" apply false
}

include(":app")
