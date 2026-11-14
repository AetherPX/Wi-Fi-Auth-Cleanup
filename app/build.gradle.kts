plugins {
    alias(libs.plugins.agp.app)
}

val stableSigningFile = rootProject.file("signing/wifi-password-guard.p12")
val stableSigningPassword = "REDACTED_SIGNING_PASSWORD"

check(stableSigningFile.isFile) {
    "Missing stable signing key: ${stableSigningFile.absolutePath}"
}

android {
    namespace = "io.github.wifipasswordguard"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.wifipasswordguard"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        create("stable") {
            storeFile = stableSigningFile
            storePassword = stableSigningPassword
            keyAlias = "wifi-password-guard"
            keyPassword = stableSigningPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
