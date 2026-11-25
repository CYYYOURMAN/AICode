import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = keystoreProps.getProperty("storeFile")

// 服务器地址与密钥不再编进 APK：开源版由「连接服务器」向导在手机上填写，
// 存 SharedPreferences（见 src/main/java/com/aicode/app/data/ServerConfig.kt），不再读 local.properties。

android {
    namespace = "com.aicode.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicode.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 114
        versionName = "0.12.37"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        // 稳定版：正常包名 com.aicode.app
        create("stable") {
            dimension = "channel"
            manifestPlaceholders["appName"] = "AIcode"
        }
        // 测试版：包名 com.aicode.app.beta，可以和稳定版同时安装
        create("beta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appName"] = "AIcode 测试"
        }
    }

    buildTypes {
        getByName("debug") {
            // debug 与 release 用同一签名：覆盖安装不需要卸载，跑仪器测试不会把 App 删掉
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")

    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.00"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
