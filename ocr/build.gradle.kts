plugins {
    alias(libs.plugins.android.library)
}

// Fully on-device OCR (PP-OCRv5 via ONNX Runtime), ported from ente/mobile_ocr. No network code lives here.
android {
    namespace = "io.github.conchad.smartgarage.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
