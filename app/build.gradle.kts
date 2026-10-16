plugins {
    alias(libs.plugins.android.application)
}

import java.util.Base64

// Persistent debug keystore: CI runners are ephemeral, so without this every
// build gets a fresh random debug key and Android refuses to update-install
// over the previous build (signature mismatch -> must uninstall first).
// The keystore bytes are base64'd in debug.keystore.b64 (debug key only,
// safe to commit; NOT a release key).
val debugKeystoreFile = file("debug.keystore")
if (!debugKeystoreFile.exists()) {
    debugKeystoreFile.writeBytes(
        Base64.getDecoder().decode(file("debug.keystore.b64").readText().trim()),
    )
}

android {
    namespace = "io.github.lujinxin.nextep"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.loveqianool.freewindow"
        minSdk = 35
        targetSdk = 36
        versionCode = 11
        versionName = "1.2.3"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystoreFile
            storePassword = "nextep-debug"
            keyAlias = "nextepdebug"
            keyPassword = "nextep-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // The framework provides this API at runtime; it must never be packaged in the APK.
    compileOnly("io.github.libxposed:api:102.0.0")

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("androidx.viewpager:viewpager:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
