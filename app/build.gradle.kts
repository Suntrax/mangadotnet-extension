plugins {
    alias(libs.plugins.android.application)
}

import java.util.Properties
import java.io.FileInputStream

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("local.properties")

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.blissless.mangadotnet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.blissless.mangadotnet"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // The release config is only created when local.properties defines
        // storeFile/storePassword/keyAlias/keyPassword. Otherwise release builds
        // fall back to the debug signing key (still installable for testing).
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystoreProperties.containsKey("storeFile")) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "src/main/keepRules/rules.keep"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Empty! We only use built-in Android APIs to keep APK size under 50KB.
}
