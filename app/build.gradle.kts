plugins {
    alias(libs.plugins.android.application)
}

import java.util.Base64

// Single source of truth for the version. Previously the version lived
// in three places that drifted apart (Gradle 1.2.4/12, module.prop
// 1.2.5/13, a hardcoded log string v1.2.5), and the installed version
// is whatever Gradle says — so Gradle owns it, and the other two are
// generated from it: module.prop by the generateModuleProp task below,
// the log line by BuildConfig (generated from defaultConfig).
val appVersionCode = 13
val appVersionName = "1.2.5"

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

// module.prop is read by LSPosed/Vector from the APK, but it must match
// the installed (Gradle) version. Generate it from the single source
// above on every build, before resources are merged, so it can never
// drift again. The generated file lives in src (that is where AGP
// packages it from); its content is deterministic, so committing the
// generated result is harmless.
val generateModuleProp = tasks.register("generateModuleProp") {
    inputs.property("versionName", appVersionName)
    inputs.property("versionCode", appVersionCode)
    val propFile = file("src/main/resources/META-INF/xposed/module.prop")
    outputs.file(propFile)
    doLast {
        propFile.writeText(
            """
            id=nextep
            name=FreeWindow
            version=$appVersionName
            versionCode=$appVersionCode
            author=loveqianool
            description=Free windows + per-window mute LSPosed module (Recents-managed)
            minApiVersion=101
            targetApiVersion=102
            staticScope=false
            exceptionMode=protective
            """.trimIndent() + "\n",
        )
    }
}
tasks.named("preBuild") { dependsOn(generateModuleProp) }

android {
    namespace = "io.github.loveqianool.freewindow"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    buildFeatures {
        // For BuildConfig.VERSION_NAME/VERSION_CODE in ServerHook's log
        // line — the third place the version used to be hardcoded.
        buildConfig = true
    }

    defaultConfig {
        applicationId = "io.github.loveqianool.freewindow"
        minSdk = 35
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

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

    // Only recyclerview is actually used (app panel list); the UI is
    // hand-built Views, no AppCompat/Material/ViewPager, no res dir.
    implementation(libs.androidx.recyclerview)
    testImplementation("junit:junit:4.13.2")
}
