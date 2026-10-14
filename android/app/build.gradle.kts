plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.ishmeet.ioniq6prox"
    compileSdk = 35

    defaultConfig {
        applicationId = "ca.ishmeet.ioniq6prox"
        minSdk = 31          // Android 12+
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // A fixed signing key committed to this private repo, so each new build installs over the
    // previous one. (Android refuses updates signed with a different key.)
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing.keystore")
            storePassword = "ioniq6prox"
            keyAlias = "ioniq6prox"
            keyPassword = "ioniq6prox"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
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
