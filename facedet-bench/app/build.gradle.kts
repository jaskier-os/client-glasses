plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.test.facedetbench"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.test.facedetbench"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Exact production ML Kit version
    implementation("com.google.mlkit:face-detection:16.1.7")
}
