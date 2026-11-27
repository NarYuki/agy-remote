plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Push notifications (FCM) need app/google-services.json from your Firebase project. It is never
// committed (see docs/push-android.md at the repo root); without it the app builds and runs, but can't get a push token.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("AGY: app/google-services.json not found, building without Firebase (no push notifications). See docs/push-android.md.")
}

/**
 * Release signing. Reads, in this order, a Gradle property (e.g. in ~/.gradle/gradle.properties,
 * never in this repo) or an environment variable of the same name:
 *   AGY_UPLOAD_STORE_FILE      absolute path of the upload keystore (.jks)
 *   AGY_UPLOAD_STORE_PASSWORD
 *   AGY_UPLOAD_KEY_ALIAS
 *   AGY_UPLOAD_KEY_PASSWORD
 * Fallback when they aren't set: the release build is signed with the debug key, which is fine for
 * local testing but is rejected by Play. See "Building from source" in the root README.
 */
fun signingValue(name: String): String? =
    (findProperty(name) as? String)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val uploadStoreFile = signingValue("AGY_UPLOAD_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "moe.n4tsu.agyremote"
    compileSdk = 37

    defaultConfig {
        applicationId = "moe.n4tsu.agyremote"
        minSdk = 31
        targetSdk = 36
        // Matches the iOS app (project.yml MARKETING_VERSION / CURRENT_PROJECT_VERSION).
        versionCode = 3
        versionName = "1.2.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        if (uploadStoreFile != null) {
            create("upload") {
                storeFile = uploadStoreFile
                storePassword = signingValue("AGY_UPLOAD_STORE_PASSWORD")
                keyAlias = signingValue("AGY_UPLOAD_KEY_ALIAS")
                keyPassword = signingValue("AGY_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug").also {
                logger.warn("AGY: no upload keystore configured, release is signed with the debug key (not for Play).")
            }
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        // Store .so files uncompressed and page-aligned in the APK (needed for 16 KB page devices).
        jniLibs { useLegacyPackaging = false }
    }
    androidResources {
        // The 20 languages of the iOS string catalogs.
        localeFilters += listOf("en", "ar", "de", "es", "fr", "iw", "hi", "it", "ja", "kk", "ko", "ms",
            "nl", "pt-rBR", "ru", "th", "tr", "vi", "b+zh+Hans", "b+zh+Hant")
    }
}

kotlin { jvmToolchain(17) }

/** Builds the Rust JNI library into src/main/jniLibs (needs the NDK and the Rust Android targets). */
val buildRust by tasks.registering(Exec::class) {
    group = "native"
    workingDir = rootProject.projectDir
    commandLine("bash", "native/build-rust.sh")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Firebase pulls in an old Fragment; registerForActivityResult needs 1.3+.
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // Liquid Glass (Kyant0/AndroidLiquidGlass, Apache-2.0)
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1") // Capsule, used by the catalog components
    // QR scanner
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // Offline QR decoding (Apache-2.0); no network or Play services.
    implementation("io.github.zxing-cpp:android:3.1.1")
    // Push notifications (data-only FCM; the app decrypts and shows them itself)
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-messaging")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM unit tests
}
