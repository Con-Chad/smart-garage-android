import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Release signing (CP13 supplies the real values as GitHub secrets with exactly these names):
//   ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD
// Resolution order: environment variable, then the git-ignored keystore.properties next to the root
// project, then (local test builds only) the SDK debug keystore so `assembleRelease` is installable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }
val releaseKeystorePath = signingValue("ANDROID_KEYSTORE_PATH")
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
val releaseSignedWithDebugKey = releaseKeystorePath == null

android {
    namespace = "io.github.conchad.smartgarage"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.conchad.smartgarage"
        // Android 12: dynamic color and the current permission model; the only target is a recent Pixel (D-A01).
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (releaseSignedWithDebugKey) {
                logger.lifecycle("release signed with DEBUG keystore (local test only)")
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                storeFile = file(releaseKeystorePath!!)
                storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = signingValue("ANDROID_KEY_ALIAS")
                keyPassword = signingValue("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the signed release build instead of clashing with its signature.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // ONNX Runtime (:ocr) ships native libs per ABI: the Pixel plus the x86_64 emulator for debugging.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            // The only target is a recent Pixel (D-A01): ship arm64 native code only (no armeabi-v7a/x86/x86_64).
            ndk { abiFilters += "arm64-v8a" }
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI (CP13) signs with the real keystore from secrets; locally this falls back to the debug key.
            signingConfig = signingConfigs.getByName("release")
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

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

// Compose compiler stability/metrics reports, opt-in only: ./gradlew :app:compileReleaseKotlin -PcomposeReports=true
// Output lands in app/build/compose_compiler (D-A39).
composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
    if (providers.gradleProperty("composeReports").orNull == "true") {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":ocr"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    // UnifiedPush connector only (D-A29): no connector-ui (appcompat) and never the embedded FCM distributor.
    implementation(libs.unifiedpush.connector)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
