plugins { id("com.android.library") }

android {
    namespace = "ru.localvoices.gigaam"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 26; ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.pickFirsts += "**/libc++_shared.so" }
}

dependencies { }
