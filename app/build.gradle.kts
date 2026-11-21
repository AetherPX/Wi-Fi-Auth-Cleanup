import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
}

val signingPropertiesFile = rootProject.file("signing.properties")
check(signingPropertiesFile.isFile) {
    "Missing signing.properties; see signing/README.md"
}

val signingProperties = Properties().apply {
    signingPropertiesFile.inputStream().use { load(it) }
}

fun signingProperty(name: String): String =
    signingProperties.getProperty(name)?.takeIf(String::isNotBlank)
        ?: error("Missing signing property: $name")

val stableSigningFile = rootProject.file(signingProperty("storeFile"))
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
        versionCode = 11
        versionName = "1.3.2"
    }

    signingConfigs {
        create("stable") {
            storeFile = stableSigningFile
            storePassword = signingProperty("storePassword")
            keyAlias = signingProperty("keyAlias")
            keyPassword = signingProperty("keyPassword")
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
