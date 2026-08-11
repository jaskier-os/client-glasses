plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.repository.glasses.btmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.repository.glasses.btmanager"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation(project(":glasses-tracing"))
}
