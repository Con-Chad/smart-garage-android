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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
