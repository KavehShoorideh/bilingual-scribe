plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.bscribe.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // MigrationTestHelper loads the exported schema JSON from assets at
    // runtime; without this it fails with FileNotFoundException.
    sourceSets {
        getByName("test") { assets.srcDirs("$projectDir/schemas") }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The schema JSON is produced by KSP but consumed from unit-test assets, and
// nothing declares that ordering — so on a clean build the asset merge can run
// first and the newest schema silently goes missing, failing migration tests
// with FileNotFoundException.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("UnitTestAssets") }
    .configureEach { dependsOn("kspDebugKotlin") }

dependencies {
    api(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.turbine)
}
