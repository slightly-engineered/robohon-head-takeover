plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.colson.projtest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.colson.projtest"
        minSdk = 21
        targetSdk = 21
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies() {
}