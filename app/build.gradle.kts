import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.stroexd.hsdecktracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stroexd.hsdecktracker"
        minSdk = 26
        targetSdk = 36
        // The release workflow counts versions up; Play needs a higher code for every upload
        versionCode = providers.gradleProperty("versionCode").orNull?.toInt() ?: 3
        versionName = providers.gradleProperty("versionName").orNull ?: "1.2.0"

        ndk {
            // ML Kit ships native libraries; real devices are ARM only
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Only the release workflow has the upload key; other release builds are signed with the debug key
    val uploadKeystore = providers.environmentVariable("UPLOAD_KEYSTORE").orNull?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storeType = "PKCS12"
                storePassword = providers.environmentVariable("UPLOAD_KEY_PASSWORD").get()
                keyAlias = "upload"
                keyPassword = storePassword
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        // Sideloaded APK with everything, including background tracking through the app's accessibility service
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "BACKGROUND_TRACKING", "true")
        }
        // Google Play restricts accessibility services, so this build tracks via screen sharing only
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "BACKGROUND_TRACKING", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    // Play: the text recognition model comes from Play services, so the bundle carries no native OCR libraries
    "githubImplementation"(libs.mlkit.text.recognition)
    "playImplementation"(libs.mlkit.text.recognition.play)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
