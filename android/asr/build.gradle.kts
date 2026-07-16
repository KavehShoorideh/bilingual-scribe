plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.bscribe.asr"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // arm64 only: the FP4 is arm64 and whisper.cpp NEON kernels need it.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // M1a wires externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }
    // against ../third_party/whisper.cpp (GGML_VULKAN=OFF, CPU only).
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
