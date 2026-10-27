import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdk = 36
    namespace = "com.ceonix.setup"

    defaultConfig {
        applicationId = "com.ceonix.setup"
        // Runs on the rider's phone, which is also the phone running Android Auto.
        minSdk = 21
        targetSdk = 36
        versionCode = 10
        versionName = "3.0"
        setProperty("archivesBaseName", "${applicationId}_${versionName}")
        // Ship only the languages this app is actually translated into. Without this the
        // libraries' own translations ride along and Play lists ~80 languages as supported.
        // Keep in step with the values-* folders under src/main/res.
        resourceConfigurations += setOf(
            "en", "ar", "cs", "de", "es", "es-rES", "hu", "it", "ja", "ka", "ko", "nl",
            "pl", "pt-rBR", "ro", "ru", "tr", "uk", "vi", "zh-rTW"
        )
    }

    signingConfigs {
        create("release") {
            // Same upload key and the same secrets.properties entries as the head unit app.
            val signingPropsFile = rootProject.file("secrets.properties")
            val props = Properties()
            if (signingPropsFile.exists()) {
                FileInputStream(signingPropsFile).use { props.load(it) }
            }
            fun secret(name: String): String? = props.getProperty(name) ?: System.getenv(name)

            secret("HEADUNIT_KEYSTORE_FILE")?.let { storeFile = rootProject.file(it) }
            keyAlias = secret("HEADUNIT_KEY_ALIAS")
            storePassword = secret("HEADUNIT_KEYSTORE_PASSWORD")
            keyPassword = secret("HEADUNIT_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
        }
        getByName("release") {
            // Small setup tool: not worth the risk of shrinking it.
            isMinifyEnabled = false
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        abortOnError = false
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Shared wire protocol with the head unit — the one thing both sides must agree on.
    implementation(project(":contract"))
}
