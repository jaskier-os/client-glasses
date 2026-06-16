plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.repository.glasses.capture"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.repository.glasses.capture"
        minSdk = 28
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                // arm64-v8a only (matches abiFilters); C++17.
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    androidResources {
        // Keep model blobs uncompressed so they can be streamed straight from the
        // APK. dlc/bin are the QNN model + cached HTP context artifacts.
        noCompress += listOf("onnx", "tflite", "dlc", "bin")
    }

    packaging {
        // The QNN HTP skel (libQnnHtpV73Skel.so) must be a real file on disk so
        // fastRPC can load it onto the DSP via ADSP_LIBRARY_PATH. Legacy packaging
        // extracts jniLibs to the app's nativeLibraryDir (matches the manifest
        // android:extractNativeLibs="true").
        jniLibs {
            useLegacyPackaging = true
            // The onnxruntime-android-qnn AAR bundles QNN 2.27 backend libs, but our
            // SplitterNet DLC / on-device-prepared context were built with QAIRT 2.47
            // and the 2.27 deserializer rejects them (QNN_CONTEXT_ERROR_BINARY_VERSION,
            // err 0x7532). We ship the matching 2.47 libs in src/main/jniLibs and use
            // pickFirst so the jniLibs copy (merged ahead of dependency AARs) wins over
            // the AAR's 2.27 copy, instead of failing the build on the name clash.
            pickFirsts += listOf(
                "lib/arm64-v8a/libQnnHtp.so",
                "lib/arm64-v8a/libQnnHtpV73Stub.so",
                "lib/arm64-v8a/libQnnHtpV73Skel.so",
                "lib/arm64-v8a/libQnnSystem.so",
                "lib/arm64-v8a/libQnnHtpPrepare.so",
                "lib/arm64-v8a/libQnnHtpNetRunExtensions.so",
                "lib/arm64-v8a/libQnnModelDlc.so",
                "lib/arm64-v8a/libQnnCpu.so"
            )
        }
    }
}

dependencies {
    implementation(project(":glasses-tracing"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.20.0")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
    // GpuDelegate() resolves GpuDelegateFactory$Options from this -api artifact at
    // runtime; tensorflow-lite-gpu does not pull it transitively, so without this
    // the GPU delegate fails class resolution and silently falls back to CPU.
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu-delegate-plugin:0.4.4")
    testImplementation("junit:junit:4.13.2")
}
