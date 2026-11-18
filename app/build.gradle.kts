plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Version comes from git so every build can be installed over the previous one:
// versionCode must increase, otherwise Android refuses the update.
fun gitOutput(vararg args: String): String = try {
    val process = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
} catch (e: Exception) {
    ""
}

val gitCommitCount = gitOutput("rev-list", "--count", "HEAD").toIntOrNull() ?: 1
val gitTag = gitOutput("describe", "--tags", "--abbrev=0").removePrefix("v")
val gitDescribe = gitOutput("describe", "--tags", "--always").removePrefix("v").ifBlank { "0.0.0" }

val buildVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: maxOf(1, gitCommitCount)
val buildVersionName = (findProperty("versionName") as String?)
    ?: if (gitTag.isNotBlank() && gitDescribe == gitTag) gitTag else gitDescribe

android {
    namespace = "com.simplecalculator.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.simplecalculator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersionCode
        versionName = buildVersionName
        resourceConfigurations += listOf("en", "tr")
        // Debug-only harness activities bypass the vault PIN. They are exported
        // only in explicit local test builds; CI/release builds keep them
        // unexported so another app cannot open the feed around the lock.
        manifestPlaceholders["debugToolsExported"] =
            (findProperty("debugToolsExported") as String?) ?: "false"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.0.12077973"

    defaultConfig {
        ndk {
            // Phones only: the emulator build is only used for smoke tests.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")
    implementation("androidx.media3:media3-ui:1.5.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
