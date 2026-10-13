plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ysogabe.miharashi.panel"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ysogabe.miharashi.panel"
        // Echo Show 5 の LineageOS 18.1 は Android 11(API 30)
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 依存は最小にする(設計書 §5.6)。HTTP は HttpURLConnection、JSON は Android 標準の org.json。
    testImplementation("junit:junit:4.13.2")
    // android.jar の org.json はスタブなので、JVM の単体テストでは本物を使う
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
