plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bjp.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bjp.itantra"
        minSdk = 21          // matches the sherpa-onnx reference app
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // Self-signed, generated 21 Sep 2026. Fine for sideloading and demos; if this ever
            // goes to a store, replace it and keep the new keystore safe — losing it means you
            // can never update the app under the same identity again.
            storeFile = file("keystore/itantra-release.jks")
            storePassword = "itantra2026"
            keyAlias = "itantra"
            keyPassword = "itantra2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false      // keep off: the sherpa-onnx JNI classes need their names
            isShrinkResources = false    // the "resources" are 470 MB of .onnx — do not touch them
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
    kotlinOptions { jvmTarget = "17" }

    // The model files are already compressed; re-compressing a 348 MB onnx bloats the APK and
    // slows every build. noCompress keeps them as-is.
    androidResources {
        noCompress += listOf("onnx", "txt", "bin")
    }

    packaging {
        jniLibs {
            // Ship only arm64 by default. Add armeabi-v7a if you must support 32-bit devices.
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // NOTE: there is no sherpa-onnx Maven dependency. The Kotlin API files are vendored into
    // app/src/main/java/com/k2fsa/sherpa/onnx/ and the native .so files go in
    // app/src/main/jniLibs/arm64-v8a/. See README.md.
}
