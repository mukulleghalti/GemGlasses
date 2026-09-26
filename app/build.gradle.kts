import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

android {
    namespace = "com.geno.veyra"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.geno.veyra"
        minSdk = 29
        targetSdk = 35
        // Release branch: every CI build gets a unique, increasing
        // versionCode so public APKs update cleanly over each other.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // === Existing fields ===
        buildConfigField("boolean", "USE_REAL_GLASSES", "true")

        manifestPlaceholders["metaApplicationId"] = secret("META_APPLICATION_ID", "0")
        manifestPlaceholders["metaClientToken"] = secret("META_CLIENT_TOKEN", "")
    }

    signingConfigs {
        create("release") {
            // Release-branch CI only: PKCS12 keystore decoded from the
            // RELEASE_KEYSTORE_BASE64 repo secret. See
            // .github/workflows/release.yml.
            storeType = "PKCS12"
            storeFile = file(System.getenv("RELEASE_KEYSTORE_PATH") ?: "")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Signed on CI from the RELEASE_KEYSTORE_* secrets (release
            // branch only). Without them this builds an unsigned APK.
            if (!System.getenv("RELEASE_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Never debuggable: this is the shareable build.
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDir("src/realGlasses/java")
        }
    }
}

dependencies {
    // ... (your dependencies remain unchanged)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation("com.google.android.gms:play-services-location:21.3.0")
    // ML Kit thin clients (models download via Play Services): QR/barcode + OCR.
    implementation(libs.play.services.mlkit.barcode.scanning)
    implementation(libs.play.services.mlkit.text.recognition)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation("com.facebook.soloader:soloader:0.10.5")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.facebook.infer.annotation:infer-annotation:0.18.0")
    implementation("com.facebook.fresco:fbcore:2.6.0")

    implementation(libs.mwdat.core)
    implementation(libs.mwdat.camera)
    implementation(libs.mwdat.display)
    implementation(libs.mwdat.mockdevice)

    // Vosk on-device wake-word engine. The AAR bundles its native ABIs, so no
    // NDK config is needed. The acoustic model (~40 MB) is downloaded at
    // runtime on first use, never committed to git.
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xskip-metadata-version-check",
            "-Xannotation-default-target=param-property"
        )
    }
}
