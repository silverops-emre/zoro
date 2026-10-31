import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ZORO_V2_SERVER_CONFIG
// Sunucu adresi ve sifreler android/zoro.properties dosyasindan okunur.
// Bu dosya git'e girmez; ornegi zoro.properties.example.
val zoroProps = Properties().apply {
    val file = rootProject.file("zoro.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun zoroProp(key: String, default: String = ""): String =
    (zoroProps.getProperty(key) ?: default)
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {
    namespace = "com.zaferyilmaz.zoro"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zaferyilmaz.zoro"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "ZORO_SERVER_URL", "\"${zoroProp("zoro.serverUrl", "https://37-27-156-54.sslip.io")}\"")
        buildConfigField("String", "ZORO_BASIC_USER", "\"${zoroProp("zoro.basicUser")}\"")
        buildConfigField("String", "ZORO_BASIC_PASSWORD", "\"${zoroProp("zoro.basicPassword")}\"")
        buildConfigField("String", "ZORO_API_KEY", "\"${zoroProp("zoro.apiKey")}\"")

        // Yalnizca debug derlemede yerel http sunucusuna izin ver.
        manifestPlaceholders["usesCleartextTraffic"] = "false"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}