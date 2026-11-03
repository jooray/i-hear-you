import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// MarmotKit (White Noise / Marmot messaging): generated Kotlin bindings plus native libraries,
// fetched and checksum-verified by tools/marmotkit/fetch.sh into this git-ignored directory.
val marmotKitDir = layout.projectDirectory.dir("src/marmotkit")

// Release signing with the shared publisher key; the properties file stays outside the repo.
val keystoreProperties = Properties().apply {
    val path = System.getenv("APP_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.apk-signing-keystore/signing.properties"
    File(path).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.ihearyou"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ihearyou"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
    }
    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // no minify: R8 would break MarmotKit's JNA/UniFFI bindings
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig { resourceConfigurations += listOf("en", "sk", "cs", "es", "de") }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // compressed native libraries keep the download small; MarmotKit's is ~52 MB uncompressed
        jniLibs.useLegacyPackaging = true
    }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    sourceSets {
        getByName("main") {
            kotlin.srcDir(marmotKitDir.dir("kotlin"))
            jniLibs.srcDir(marmotKitDir.dir("jniLibs"))
        }
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // MarmotKit's UniFFI bindings call the native library through JNA
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
