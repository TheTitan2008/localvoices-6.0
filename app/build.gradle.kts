plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "ru.localvoices"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "ru.localvoices"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        manifestPlaceholders["appLabel"] = "Локальные голоса"
        buildConfigField("String", "ASR_BACKEND", "\"whisper\"")
        buildConfigField("boolean", "DEFAULT_EXPERIMENTAL_CASCADE", "false")
        buildConfigField("String", "APP_EDITION", "\"3.1\"")
        buildConfigField("String", "DIARIZATION_BACKEND", "\"hdbscan\"")
        buildConfigField("String", "SPEAKER_ENCODER", "\"speaker\"")
        buildConfigField("boolean", "WORD_LEVEL_ATTRIBUTION", "false")
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    flavorDimensions += "asr"
    productFlavors {
        create("whisper") {
            dimension = "asr"
            manifestPlaceholders["appLabel"] = "Локальные голоса"
            buildConfigField("String", "ASR_BACKEND", "\"whisper\"")
        }
        create("gigaam") {
            dimension = "asr"
            applicationIdSuffix = ".gigaam"
            versionNameSuffix = "-GigaAM"
            manifestPlaceholders["appLabel"] = "Локальные голоса — GigaAM"
            buildConfigField("String", "ASR_BACKEND", "\"gigaam\"")
            externalNativeBuild { cmake { arguments += listOf("-DLOCALVOICES_GIGAAM=ON") } }
        }
        create("whisper41") {
            dimension = "asr"
            applicationIdSuffix = ".v41.whisper"
            versionNameSuffix = "-4.1-Whisper"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.1 — Whisper"
            buildConfigField("String", "ASR_BACKEND", "\"whisper\"")
            buildConfigField("String", "APP_EDITION", "\"4.1 Whisper\"")
        }
        create("gigaam41") {
            dimension = "asr"
            applicationIdSuffix = ".v41.gigaam"
            versionNameSuffix = "-4.1-GigaAM"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.1 — GigaAM"
            buildConfigField("String", "ASR_BACKEND", "\"gigaam\"")
            buildConfigField("String", "APP_EDITION", "\"4.1 GigaAM\"")
            externalNativeBuild { cmake { arguments += listOf("-DLOCALVOICES_GIGAAM=ON") } }
        }
        create("gigaam50") {
            dimension = "asr"
            // Keep the package ID compatible with the proven 5.0 install,
            // but publish the improved build as a real 6.0 update.
            versionCode = 6
            versionName = "6.0"
            applicationIdSuffix = ".v50.gigaam"
            versionNameSuffix = "-GigaAM"
            manifestPlaceholders["appLabel"] = "Локальные голоса 6.0 — GigaAM"
            buildConfigField("String", "ASR_BACKEND", "\"gigaam\"")
            buildConfigField("String", "APP_EDITION", "\"6.0 GigaAM\"")
            externalNativeBuild { cmake { arguments += listOf("-DLOCALVOICES_GIGAAM=ON") } }
        }
        create("whisper40opt") {
            dimension = "asr"
            applicationIdSuffix = ".v40.optimized.whisper"
            versionNameSuffix = "-4.0-Optimized-Whisper"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.0 — Optimized Whisper"
            buildConfigField("String", "ASR_BACKEND", "\"whisper\"")
            buildConfigField("String", "APP_EDITION", "\"4.0 Optimized Whisper\"")
        }
        create("gigaam40opt") {
            dimension = "asr"
            applicationIdSuffix = ".v40.optimized.gigaam"
            versionNameSuffix = "-4.0-Optimized-GigaAM"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.0 — Optimized GigaAM"
            buildConfigField("String", "ASR_BACKEND", "\"gigaam\"")
            buildConfigField("String", "APP_EDITION", "\"4.0 Optimized GigaAM\"")
            externalNativeBuild { cmake { arguments += listOf("-DLOCALVOICES_GIGAAM=ON") } }
        }
        create("whisper41cascade") {
            dimension = "asr"
            applicationIdSuffix = ".v41.cascade.whisper"
            versionNameSuffix = "-4.1-Cascade-Whisper"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.1 — Cascade Whisper"
            buildConfigField("String", "ASR_BACKEND", "\"whisper\"")
            buildConfigField("boolean", "DEFAULT_EXPERIMENTAL_CASCADE", "true")
            buildConfigField("String", "APP_EDITION", "\"4.1 Cascade Whisper\"")
        }
        create("gigaam41cascade") {
            dimension = "asr"
            applicationIdSuffix = ".v41.cascade.gigaam"
            versionNameSuffix = "-4.1-Cascade-GigaAM"
            manifestPlaceholders["appLabel"] = "Локальные голоса 4.1 — Cascade GigaAM"
            buildConfigField("String", "ASR_BACKEND", "\"gigaam\"")
            buildConfigField("boolean", "DEFAULT_EXPERIMENTAL_CASCADE", "true")
            buildConfigField("String", "APP_EDITION", "\"4.1 Cascade GigaAM\"")
            externalNativeBuild { cmake { arguments += listOf("-DLOCALVOICES_GIGAAM=ON") } }
        }
    }
    sourceSets {
        // The GGUF is stored once in the original gigaam source set. Reuse it
        // for the separately installable comparison flavors as well.
        getByName("gigaam41").assets.srcDir("src/gigaam/assets")
        getByName("gigaam50").assets.srcDir("src/gigaam/assets")
        getByName("gigaam40opt").assets.srcDir("src/gigaam/assets")
        getByName("gigaam41cascade").assets.srcDir("src/gigaam/assets")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}
kotlin { jvmToolchain(21) }
dependencies {
    add("gigaamImplementation", project(":gigaam"))
    add("gigaam41Implementation", project(":gigaam"))
    add("gigaam50Implementation", project(":gigaam"))
    add("gigaam40optImplementation", project(":gigaam"))
    add("gigaam41cascadeImplementation", project(":gigaam"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(files("libs/sherpa-onnx-1.12.15.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
