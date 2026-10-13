import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// The fixed debug keystore is stored as base64 text so it can be committed as a text file.
val debugKeystore: File = layout.buildDirectory.file("debug-signing/debug.keystore").get().asFile.also { ks ->
    if (!ks.exists()) {
        ks.parentFile.mkdirs()
        ks.writeBytes(Base64.getDecoder().decode(file("debug.keystore.b64").readText().trim()))
    }
}
android {
    namespace = "com.aiorigin.detector"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aiorigin.detector"
        minSdk = 28
        targetSdk = 35
        versionCode = 23
        versionName = "0.23.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The phone is arm64; x86_64 is kept only so the CI emulator can run the model.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    // The detector model is memory-mapped from a copied file; storing it uncompressed keeps the
    // APK install fast and lets the asset be opened as a file descriptor.
    androidResources { noCompress += "onnx" }
    signingConfigs {
        // Fixed, intentionally public debug key (standard Android debug credentials). Without it,
        // every CI run would sign with a new random key and updates on the phone would fail.
        getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") {
            // Debug certificates cannot update release-signed installations.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// The model is not committed; tools/model/fetch-model.sh downloads and verifies it (SHA-256).
val modelAsset = file("src/main/assets/models/commfor_vit_s384.onnx")
tasks.named("preBuild") {
    doFirst {
        if (!modelAsset.exists()) throw GradleException("Missing ${modelAsset.path}: run tools/model/fetch-model.sh first")
    }
}
