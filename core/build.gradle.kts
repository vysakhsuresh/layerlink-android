plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.layerbit.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // `api` (not `implementation`): apps depending on this module reference these types
    // directly (org.webrtc.* for rendering, coroutines for the suspend-based session API), so
    // they need to be on the app module's compile classpath too, not just this module's.
    api("androidx.core:core-ktx:1.13.1")
    api("androidx.lifecycle:lifecycle-service:2.8.4")
    api("io.getstream:stream-webrtc-android:1.3.10")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:okhttp-sse:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
