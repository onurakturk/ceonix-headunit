import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

dependencies {
    // Conscrypt
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Pager for the Ride dashboard's third card slot. It already arrives transitively via
    // material 1.10.0 (same version), so declaring it changes resolution by nothing — it is
    // here so a future material bump cannot silently take it away. The AAR is minSdk 14 /
    // minCompileSdk 33, below both of our floors (github minSdk 16, compileSdk 36).
    implementation("androidx.viewpager2:viewpager2:1.1.0-beta02")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.startup:startup-runtime:1.1.1")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    // ViewModel and LiveData
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    kapt("androidx.lifecycle:lifecycle-compiler:2.6.2")

    // KTX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    testImplementation("junit:junit:4.13.2")

    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.0")
    implementation(project(":contract"))

    // Multidex
    implementation("androidx.multidex:multidex:2.0.1")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.3.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.3.5")

    // DexMaker for runtime subclassing (Hotspot Fix)
    implementation("com.linkedin.dexmaker:dexmaker:2.28.3")

    // Glide for image/GIF loading (custom loading screen)
    implementation("com.github.bumptech.glide:glide:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")

    // ZXing for QR Code generation
    implementation("com.google.zxing:core:3.5.3")
}

android {
    compileSdk = 36
    ndkVersion = "27.0.12077973"
    namespace = "com.andrerinas.headunitrevived"

    buildFeatures {
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Scan available locales at configuration time and store as BuildConfig field
    val resDir = file("src/main/res")
    val availableLocales = resDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("values-") &&
        // Filter out non-language qualifiers (night mode, screen size, etc.)
        !file.name.contains("night") &&
        !file.name.contains("land") &&
        !file.name.contains("port") &&
        !file.name.matches(Regex("values-[whsml]\\d+.*")) &&
        !file.name.matches(Regex("values-v\\d+")) &&
        // Check that it contains strings.xml (actual translation)
        file.resolve("strings.xml").exists()
    }?.map { dir ->
        // Extract locale code from directory name (e.g., "values-es" -> "es", "values-pt-rBR" -> "pt-rBR")
        dir.name.removePrefix("values-")
    }?.sorted() ?: emptyList()

    println("Detected available locales: $availableLocales")


    defaultConfig {
        applicationId = "com.ceonix.ride"
        minSdk = 16
        targetSdk = 36
        versionCode = 149
        versionName = "1.2.29"
        setProperty("archivesBaseName", "${applicationId}_${versionName}")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true

        // Store available locales in BuildConfig for runtime access
        // This is scanned at build time from values-XX directories
        buildConfigField("String", "AVAILABLE_LOCALES", "\"${availableLocales.joinToString(",")}\"")

        externalNativeBuild {
            cmake {
                cppFlags("")
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("playstore") {
            dimension = "distribution"
            minSdk = 21
        }
        create("github") {
            dimension = "distribution"
            // Default minSdk 16 from defaultConfig is used
        }
    }

    signingConfigs {
        getByName("debug") {
            // storeFile = file("../keystore.jkc")
            // storePassword = property("HEADUNIT_KEYSTORE_PASSWORD") as String
            // keyAlias = property("HEADUNIT_KEY_ALIAS") as String
            // keyPassword = property("HEADUNIT_KEY_PASSWORD") as String
        }

        create("release") {
            // Play upload key. Path, alias and both passwords come from secrets.properties
            // (git-ignored) or the environment — nothing about the key lives in the repo.
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
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-project.txt")
            // No upload key configured: sign with the debug key so the minified build can still
            // be installed for testing (and over an existing debug install). Play rejects
            // debug-signed uploads, so this cannot reach the store by accident.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                println("Release signing: no upload key configured, using the debug key (not uploadable)")
                signingConfigs.getByName("debug")
            }
            multiDexKeepProguard = file("multidex-config.pro")
        }
        getByName("debug") {
            isDebuggable = true
            isJniDebuggable = true
            multiDexKeepProguard = file("multidex-config.pro")
        }
    }

    lint {
        abortOnError = false
        disable += "PackagedPrivateKey"
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

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                var outputFileName = "${variant.applicationId}_${variant.versionName}_debug.apk"
                if(variant.buildType.name == "release") {
                    outputFileName = "${variant.applicationId}_${variant.versionName}.apk"
                    output.outputFileName = outputFileName
                }
                output.outputFileName = outputFileName
            }
    }
}
