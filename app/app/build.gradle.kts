import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------
// Release signing credentials, loaded from app/keystore.properties (untracked — see
// docs/RELEASE.md). They are not written into this file so the password stays out of git
// history. The first keystore's password WAS committed, and that key was rotated out on
// 16 Oct 2026 because of it. The properties file and the .jks it names are the two things
// that must survive this machine: lose them and an installed iTantra can never be updated
// in place again — only uninstalled and reinstalled.
// ---------------------------------------------------------------------------------------
val keystoreProps = Properties()
run {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { keystoreProps.load(it) }
}

android {
    namespace = "com.bjp.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bjp.itantra"
        // 23, not the reference app's 21. This app's own calls — AudioTrack.Builder (playback),
        // Context.getColor, TextView.setTextAppearance(int), getSystemService(Class) — are API 23;
        // on 21/22 they would crash at runtime. Lint flags every one, and compatibility shims for
        // that dead 0.1% of devices would be untested paths. The floor is where the app runs.
        minSdk = 23
        targetSdk = 33
        // versionCode is what Android compares when deciding whether one APK can update
        // another; it must rise on EVERY build you hand to a phone. 1 was the unreleased
        // 0.1.0 development line, so the first proper release starts at 2. versionName is
        // the human-readable one shown in About and Settings. Policy: docs/RELEASE.md.
        versionCode = 3
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") == null) {
                logger.warn(
                    "keystore.properties is missing — release builds cannot be signed. " +
                        "See docs/RELEASE.md; restore that file and the .jks it names."
                )
            } else {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A distinct package (com.bjp.itantra.debug), so a debug build and the sideloaded
            // release sit side by side on the same phone. Without it, installing one over the
            // other fails on the signature and the only fix is uninstalling — which wipes the
            // log file and the pending-message state along with it.
            applicationIdSuffix = ".debug"
        }
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

    // ONNX Runtime for the optional Laya triage layer (app/src/main/java/com/bjp/itantra/laya/).
    //
    // Vendored as a jar + one thin JNI library instead of a Maven AAR, and taken from
    // csukuangfj's 1.28.2 release — the *same* release the libonnxruntime.so in jniLibs comes
    // from. setup-project.sh verifies the two files are byte-identical (sha256), so this adds a
    // Java API and nothing else: ONE ONNX Runtime stays in the APK.
    //
    // Why not the Maven AAR (1.28.0 or 1.29.0)? Its libonnxruntime.so exports OrtGetApiBase with
    // version tag VERS_1.28.0 / VERS_1.29.0, and Android's linker matches versioned symbols
    // exactly — sherpa's JNI requires OrtGetApiBase@VERS_1.28.2. Swapping that library in would
    // have made ASR and TTS fail to load, at runtime, with no build warning. The matched jar
    // keeps the speech path on exactly the binary it was measured with. See Addendum 4 of
    // docs/LAYA_INTEGRATION_FINDINGS.md.
    implementation(files("libs/onnxruntime-android-1.28.2.jar"))

    // Unit tests for the pure-Kotlin decision logic (QualityGate, LanguageFilter). These classes
    // touch no Android API, so they run on the JVM via :app:testDebugUnitTest -- no device needed.
    // JUnit 4 is resolved from the local Gradle cache; there is no network dependency.
    testImplementation("junit:junit:4.13.2")

    // org.json for the unit tests, and ONLY for the unit tests.
    //
    // In an Android unit test every org.json method comes from the android.jar stub and throws
    // "not mocked" — so Message.toJson/fromJson (the delivery-receipt envelope) could not be
    // round-tripped on the JVM. This puts the real library on the test classpath; the app itself
    // keeps using the platform's org.json and the APK is unchanged. Version 20180813 matches what
    // is already in the local Gradle cache, so tests still need no network.
    testImplementation("org.json:json:20180813")

    // NOTE: there is no sherpa-onnx Maven dependency. The Kotlin API files are vendored into
    // app/src/main/java/com/k2fsa/sherpa/onnx/ and the native .so files go in
    // app/src/main/jniLibs/arm64-v8a/. See README.md.
}
