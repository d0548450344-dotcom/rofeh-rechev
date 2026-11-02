plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

/*
 * Release signing. The key never lives in the repository: CI decodes it from the
 * SIGNING_KEYSTORE_B64 secret into a temp file and passes its path and password here.
 * Every build signed with the same key installs over the previous one and keeps the
 * user's garage. Without the variables, release falls back to debug signing.
 */
val signingFile: String? = System.getenv("SIGNING_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseKey = signingFile != null && signingPassword != null && file(signingFile).isFile

android {
    namespace = "car.doctor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "car.doctor.app"
        minSdk = 26
        targetSdk = 35
        // CI sets VERSION_CODE from the run number, so every build is an upgrade.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(signingFile!!)
                storePassword = signingPassword
                keyAlias = "rofeh-rechev"
                keyPassword = signingPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        // Lint runs in CI on its own; it must not block producing the APK.
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":simulation"))
    implementation(project(":live"))
    implementation(project(":data"))
    // simulation and data keep these as implementation dependencies.
    // They are not transitive, and the APK still needs them at runtime.
    implementation(project(":transport"))
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
