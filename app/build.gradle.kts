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
    namespace = "com.lpecom.gemglasses"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lpecom.gemglasses"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_URL", "\"${secret("GEMGLASSES_BACKEND_URL", "")}\"")
        buildConfigField("String", "APP_SECRET", "\"${secret("TOKEN_APP_SECRET", "")}\"")
        buildConfigField("boolean", "USE_REAL_GLASSES", "true")

        manifestPlaceholders["metaApplicationId"] = secret("META_APPLICATION_ID", "0")
        manifestPlaceholders["metaClientToken"] = secret("META_CLIENT_TOKEN", "")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xskip-metadata-version-check"
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    // FIX FOR LOCATION ERRORS (Google Play Services)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("com.facebook.soloader:soloader:0.10.5")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.facebook.infer.annotation:infer-annotation:0.18.0")
    implementation("com.facebook.fresco:fbcore:2.6.0") // Provides Facebook LoggingDelegates & debug loggers
    // HARD-PLACED META SDK (Points to your local app/libs folder)
    // implementation(files("libs/mwdat-core-0.9.0.aar"))
    // implementation(files("libs/mwdat-camera-0.9.0.aar"))
    implementation("com.meta.wearable:mwdat-core:0.9.0")
    implementation("com.meta.wearable:mwdat-camera:0.9.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
