import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// ccache dramatically speeds up the llama.cpp/NDK rebuild on CI by caching object
// compilation. Only wire it in when ccache is actually on PATH so local builds without
// it are unaffected (CI installs it via hendrikmuhs/ccache-action).
val ccacheAvailable: Boolean = System.getenv("PATH")
    ?.split(File.pathSeparator)
    ?.any { dir -> File(dir, "ccache").canExecute() } == true

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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64 only. The daily-driver device and the Apple Silicon dev machine's
        // native emulator image are both arm64; x86_64 only added ~120 MB for x86
        // emulators/Chromebooks we don't target. Use an arm64-v8a AVD for emulation.
        ndk {
            abiFilters += "arm64-v8a"
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
                if (ccacheAvailable) {
                    arguments += "-DCMAKE_C_COMPILER_LAUNCHER=ccache"
                    arguments += "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
                }
                // Build only the two JNI bridge libraries (and their transitive
                // deps: llama/ggml, hoshidicts/glaze/zstd/...). This keeps the
                // hoshidicts CLI + benchmark executables out of the build.
                targets += listOf("yomidroid_llm", "yomidroid_dict")
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
    implementation("androidx.compose.material:material-icons-extended")
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

    // ONNX Runtime for Manga OCR inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.2")

    // Kuromoji Japanese morphological analyzer (for Grammar Analyzer)
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // AnkiDroid API for flashcard export
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")

    // Instrumented tests (Hoshidicts JNI round-trip on-device)
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
