plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yomidroid"
    compileSdk = 34

    // NDK for llama.cpp on-device LLM
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.yomidroid"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Only build for arm64 (most modern phones) and x86_64 (emulator)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_OPENMP=OFF"
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
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Compose (BOM manages all Compose dependency versions)
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ML Kit Japanese OCR
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // ML Kit Translation (Japanese → English fallback)
    implementation("com.google.mlkit:translate:17.0.3")

    // Room for history/dictionary
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Physics-based animations for natural FAB movement
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // RapidOCR (PaddleOCR-based ONNX engine)
    implementation(project(":OcrLibrary"))

    // Kuromoji Japanese morphological analyzer (for Grammar Analyzer)
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // AnkiDroid API for flashcard export
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
}
