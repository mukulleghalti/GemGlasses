import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Read secrets from local.properties so they never live in source control.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

// When false, the real Meta DAT SDK is not linked and the app uses the mock
// glasses backend. Enables hardware-free CI and local development.
val useRealGlasses = (project.findProperty("gemglasses.useRealGlasses") as String?)
    ?.toBoolean() ?: true

android {
    namespace = "com.lpecom.gemglasses"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lpecom.gemglasses"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injected into BuildConfig + manifest placeholders instead of hardcoding.
        buildConfigField("String", "BACKEND_URL", "\"${secret("GEMGLASSES_BACKEND_URL", "http://10.0.2.2:8787")}\"")
        buildConfigField("String", "APP_SECRET", "\"${secret("GEMGLASSES_APP_SECRET", "dev-secret")}\"")
        buildConfigField("boolean", "USE_REAL_GLASSES", useRealGlasses.toString())

        manifestPlaceholders["metaApplicationId"] = secret("META_APPLICATION_ID", "0")
        manifestPlaceholders["metaClientToken"] = secret("META_CLIENT_TOKEN", "")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The real Meta DAT integration lives in its own source dir that is only
    // compiled when the SDK is linked. Hardware-free builds (mock backend) skip
    // it entirely, so the project still compiles with no github_token.
    sourceSets {
        getByName("main") {
            if (useRealGlasses) {
                java.srcDir("src/realGlasses/java")
            }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)

    // Real Meta DAT SDK only linked when a github_token is present and the flag is on.
    if (useRealGlasses) {
        implementation("com.meta.wearable:mwdat-core:0.5.0")
            implementation("com.meta.wearable:mwdat-camera:0.5.0")
    }

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
