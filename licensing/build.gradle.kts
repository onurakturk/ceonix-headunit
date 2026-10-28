import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions

// Play Licensing (LVL) check that works offline after one successful verification.
//
// Self-contained on purpose: nothing in here knows about the head unit, so the module can be
// dropped into another app and configured through LicenseConfig. No third-party dependencies —
// the platform's Keystore, javax.crypto and java.security do all the work.
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = 36
    namespace = "com.ceonix.licensing"

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        (this as KotlinJvmOptions).let {
            it.jvmTarget = "1.8"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
