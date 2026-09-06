import java.util.Properties

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
        // 版本号与上游仓库对齐：上游 v1.2.1 = versionCode 4，本分支 v1.3.0 = 5
        versionCode = 5
        versionName = "1.3.0"
    }

    // 仓库自带自签名密钥（signing/），克隆即可构建可安装的 release 包；
    // 不想用就删掉 signing.properties，release 会退回未签名构建。
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("signing.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // 零第三方依赖：只用 android framework API，任何手机装机即用
    buildTypes {
        release {
            isMinifyEnabled = false
            if (rootProject.file("signing.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
