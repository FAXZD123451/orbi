plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.orbi.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.orbi.app"
        // Android 8.0+: necesario para dibujar sobre otras apps (TYPE_APPLICATION_OVERLAY).
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0"
        // Solo celulares ARM de 64 bits (casi todos los actuales): la APK pesa mucho menos,
        // porque MediaPipe y Vosk traen librerías nativas para cada tipo de procesador.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Cámara frontal + detección de caras en el propio celular (sin internet).
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view) // vista previa del modo creador
    implementation(libs.mlkit.face.detection)
    // Oído: reconocimiento de voz en el celular (Vosk). JNA va como .aar para Android.
    implementation(libs.vosk.android) { artifact { type = "aar" }; exclude(group = "net.java.dev.jna") }
    implementation(libs.jna) { artifact { type = "aar" } }
    // Cerebro: Gemma funcionando en el celular (MediaPipe LLM Inference).
    implementation(libs.mediapipe.genai)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
