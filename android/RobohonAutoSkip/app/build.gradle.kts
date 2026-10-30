plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.colson.robohonautoskip"

    // Robohon = Android 5.0 (API 21)
    compileSdk = 21

    defaultConfig {
        applicationId = "com.colson.robohonautoskip"
        minSdk = 21
        targetSdk = 21
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Robohon only supports Java 8
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // Compose must be OFF
    buildFeatures {
        compose = false
    }
}

dependencies {
    // NO AndroidX libraries allowed on API 21
    // NO Compose
    // NO Lifecycle
    // NO Core-KTX

    // Only legacy testing libs (optional)
    testImplementation(libs.junit)
}
