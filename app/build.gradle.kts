plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ekrancevirmeni"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ekrancevirmeni"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Ekrandaki yazıyı okuma (cihaz içinde, internetsiz)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Yedek çeviri motoru (cihaz içinde, internetsiz)
    implementation("com.google.mlkit:translate:17.0.3")
}
