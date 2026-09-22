// Top-level build file.
//
// Toolchain note: AGP 8.1.x requires JDK 17. It will NOT work on JDK 21+ or 26 — you will get
// "Unsupported class file major version". If the build fails on the JDK, that is the cause.
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
