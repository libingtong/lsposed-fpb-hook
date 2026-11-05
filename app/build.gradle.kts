plugins {
    alias(libs.plugins.agp.app)
}

// Release 签名从环境变量注入（GitHub Actions secrets），本地无密钥时自动回退 debug 签名
val ksPath: String? = System.getenv("KEYSTORE_PATH")
val hasKeystore: Boolean = ksPath != null && file(ksPath).exists()

android {
    namespace = "com.yg.fpbhook"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yg.fpbhook"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasKeystore) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 关键：否则 META-INF/xposed/* 会被打包流程剥离，模块无法被识别
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
