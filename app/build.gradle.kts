plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nightboard.keyboard68"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nightboard.keyboard68"
        minSdk = 28          // BluetoothHidDevice 需要 Android 9
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.1"
    }

    // 零第三方依赖：只用 android framework API，任何手机装机即用
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
