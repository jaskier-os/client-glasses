plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build-time config resolution. Precedence:
//   1. environment variables (CI-friendly)
//   2. local.properties (gitignored, see local.properties.example)
//   3. safe placeholder defaults supplied at call sites
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(key: String, default: String): String =
    System.getenv(key) ?: localProps.getProperty(key) ?: default

android {
    namespace = "com.repository.glasses.listener"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.repository.glasses.listener"
        minSdk = 28
        targetSdk = 34
        versionCode = 16
        versionName = "2.4.1"

        // OSINT-style ReID person "intel" lookups. Disabled by default; set
        // ENABLE_REID_OSINT=true in local.properties (or env) to enable.
        // Core face re-identification (the ReID tab / face recognition) is
        // NOT gated by this.
        buildConfigField("Boolean", "ENABLE_REID_OSINT", cfg("ENABLE_REID_OSINT", "false"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testProguardFiles("proguard-androidtest.pro")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // R8 minify + resource shrinking on debug. Our glasses deploy uses
            // the debug variant (debuggable flag enables adb debug, log verbosity,
            // StrictMode, etc). Minify trims dead code + renames app symbols;
            // consumer-rules.pro from onnxruntime/mlkit/hiddenapibypass keep
            // their own reflection targets. Our reflection into
            // android.hardware.soundtrigger.* targets framework classes which
            // R8 cannot touch anyway.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Drop Hexagon arch libs we don't need. Rokid Neo aDSP is v73; the
    // onnxruntime-android-qnn AAR bundles Stub+Skel for v68/v69/v73/v75/v79
    // plus DSPv66 + GPU backends we never use. Excluding the unused ones
    // saves ~36 MB of APK size. Keep:
    //   libQnnHtp.so            -- ARM-side dispatcher
    //   libQnnSystem.so         -- system context
    //   libQnnHtpV73Stub.so     -- v73 ARM-side stub (pairs with Skel on DSP)
    //   libQnnHtpPrepare.so     -- JIT compiler (kept for now; step C would drop)
    //   libonnxruntime.so       -- ORT core
    //   libopus_jni.so          -- our native
    packaging {
        jniLibs {
            excludes += setOf(
                "**/libQnnHtpV68Stub.so",
                "**/libQnnHtpV68Skel.so",
                "**/libQnnHtpV69Stub.so",
                "**/libQnnHtpV69Skel.so",
                "**/libQnnHtpV75Stub.so",
                "**/libQnnHtpV75Skel.so",
                "**/libQnnHtpV79Stub.so",
                "**/libQnnHtpV79Skel.so",
                "**/libQnnDspV66Stub.so",
                "**/libQnnDspV66Skel.so",
                "**/libQnnGpu.so",
                "**/libQnnCpu.so",
                "**/libQnnSaver.so",
            )
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
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
    implementation(project(":glasses-tracing"))

    // CXR-S (glasses-side BT bridge)
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20250519.061355-45")

    // AndroidX
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.tracing:tracing-ktx:1.2.0")

    // HiddenApiBypass -- required for reflection into hidden Android framework
    // APIs from our privapp. Being privapp grants permission to CALL hidden
    // APIs but does NOT automatically exempt reflective access -- only
    // platform-signed apps get that. This library flips the per-process
    // exemption list via VMRuntime internals so we can construct
    // android.hardware.soundtrigger.SoundTrigger$GenericSoundModel etc. MIT,
    // Maven Central.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // ML Kit face detection (for ReID)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Instrumented tests (androidTest)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    // ONNX Runtime with QNN (Hexagon HTP) execution provider.
    // Bumped from 1.17.0 onnxruntime-android -> 1.20.0 onnxruntime-android-qnn because the
    // QNN variant is not published at 1.17.0 on Maven Central; 1.20.0 is the earliest
    // available (see https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/).
    // The QNN variant supersedes the plain CPU build and still exposes the CPU execution
    // provider for fallback, so night-vision ONNX inference continues to work unchanged.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.20.0")
}
