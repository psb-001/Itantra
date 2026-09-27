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
            //
            // useLegacyPackaging = true COMPRESSES the native libraries instead of storing them
            // uncompressed. This is deliberate, and it is a 16 KB page-size fix — not a default.
            //
            // The problem it solves: Android 15 (API 35) devices can use 16 KB memory pages, and a
            // library stored UNCOMPRESSED in the APK must itself begin on a 16 KB boundary inside
            // the zip archive. Ours did not, so real phones showed:
            //
            //     "This app isn't 16 KB compatible. APK alignment check failed."
            //       lib/arm64-v8a/libonnxruntime.so: Uncompressed library not aligned
            //
            // Note the libraries themselves are FINE. Both .so files have every PT_LOAD segment
            // aligned to 0x4000 = 16384 bytes (verified by parsing the ELF program headers), so
            // they load correctly on a 16 KB device. Only their position in the zip was wrong.
            //
            // Compressing removes the zip-alignment requirement entirely, and costs about 15 MB
            // and a slightly slower first load. The alternative — proper 16 KB zip alignment —
            // needs AGP 8.5.1 or newer, and this project is on AGP 8.1.4 / Gradle 8.2. Upgrading
            // both would mean a Gradle 8.7+ migration with two days to the deadline, for no
            // runtime benefit, because the ELFs are already correct.
            //
            // If this is ever raised, the test is:
            //   ~/Library/Android/sdk/build-tools/*/zipalign -c -P 16 -v 4 app-release.apk
            useLegacyPackaging = true
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
