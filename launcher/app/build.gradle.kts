plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    androidResources {
        // model mowy (vosk-pl.zip) jest już spakowany – bez ponownej kompresji
        noCompress += "zip"
    }
    namespace = "pl.lawinski.radiolauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.lawinski.radiolauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.3.20"
        // radio: arm64 (Snapdragon 668S); x86_64 tylko dla emulatora
        // -PdoAuta: tylko arm64, mniejszy plik do instalacji w radiu
        ndk { abiFilters += if (project.hasProperty("doAuta")) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // bez własnego klucza: podpis debug wystarcza do instalacji z pendrive'a
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // podgląd kamery na żywo z serwera RTSP aplikacji Xiaomi Cam (127.0.0.1:18554)
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    // asystent AI w bloku „Asystent” (Claude); OpenAI idzie zwykłym HTTPS
    implementation("com.anthropic:anthropic-java:3.6.0")
    // hasło „OK BMW” rozpoznawane w radiu, bez internetu (model: assets/vosk-pl.zip, pobierz_model_mowy.sh)
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

}
