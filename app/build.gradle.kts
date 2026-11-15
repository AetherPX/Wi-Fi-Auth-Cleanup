plugins {
    alias(libs.plugins.agp.app)
}

val stableSigningFile = rootProject.file("signing/wifi-auth-cleanup.p12")
val stableSigningPassword = "REDACTED_SIGNING_PASSWORD"

check(stableSigningFile.isFile) {
    "Missing stable signing key: ${stableSigningFile.absolutePath}"
}

android {
    namespace = "io.github.aetherpx.wifiauthcleanup"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.github.aetherpx.wifiauthcleanup"
        minSdk = 33
        targetSdk = 37
        versionCode = 6
        versionName = "1.2.0"
    }

    signingConfigs {
        create("stable") {
            storeFile = stableSigningFile
            storePassword = stableSigningPassword
            keyAlias = "wifi-auth-cleanup"
            keyPassword = stableSigningPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
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
}

dependencies {
    compileOnly(libs.libxposed.api)
}
