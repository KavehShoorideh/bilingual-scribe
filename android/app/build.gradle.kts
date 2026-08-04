plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * versionCode is the commit count, so it rises monotonically with history and
 * every build is upgradeable over the last. Obtainium compares versionCode to
 * decide whether a GitHub Release is newer, so a hand-maintained constant would
 * silently stop offering updates. Falls back to 1 outside a git checkout (e.g.
 * a source tarball).
 */
val gitCommitCount: Int by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)
}

/**
 * Release signing. Credentials come from the environment (CI) or
 * ~/.gradle/gradle.properties (local) and are never committed. When they are
 * absent the release build stays unsigned rather than silently falling back to
 * the debug key — a debug-signed APK cannot upgrade a release-signed install,
 * so a quiet fallback would ship an artifact that fails to install for users.
 */
val keystorePath: String? =
    System.getenv("BSCRIBE_KEYSTORE") ?: providers.gradleProperty("bscribe.keystore").orNull

android {
    namespace = "dev.bscribe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.bscribe.app"
        minSdk = 33
        targetSdk = 35
        versionCode = gitCommitCount
        versionName = "0.1.0-m0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("BSCRIBE_KEYSTORE_PASSWORD")
                    ?: providers.gradleProperty("bscribe.keystore.password").orNull
                keyAlias = System.getenv("BSCRIBE_KEY_ALIAS")
                    ?: providers.gradleProperty("bscribe.key.alias").orNull
                keyPassword = System.getenv("BSCRIBE_KEY_PASSWORD")
                    ?: providers.gradleProperty("bscribe.key.password").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 is off until the M1a JNI surface settles; shrinking native
            // entry points needs keep rules that don't exist yet.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // The updater compares BuildConfig.VERSION_CODE against the published
        // release metadata.
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":asr"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}
