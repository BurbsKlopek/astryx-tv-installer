import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val userHome: String = System.getProperty("user.home") ?: ""
val signingDirCandidates = listOf(
    File("$userHome/Documents/Android Development/Android Signing"),
    File("$userHome/Documents/Android Signing")
)
val activeSigningDir = signingDirCandidates.firstOrNull {
    File(it, "burbsklopek.jks").isFile && File(it, "burbsklopek.jks.pass").isFile
}

// Auto-bundle the latest local Astryx Rust Launcher release APK into generated assets
// so the installer APK works offline out of the box (while still supporting GitHub Release updates
// and local file picker).
val bundledAssetDir = layout.buildDirectory.dir("generated/bundledApk")
val launcherRepoCandidates = listOf(
    rootProject.file("../astryx-tv-launcher"),
    rootProject.file("../astryx-rust-launcher")
)
val launcherRepoDir = launcherRepoCandidates.firstOrNull { File(it, "android-rust").isDirectory }
val launcherGradleFile = launcherRepoDir?.let { File(it, "android-rust/app/build.gradle.kts") }
val launcherBuiltApk = launcherRepoDir?.let { File(it, "android-rust/app/build/outputs/apk/release/app-release.apk") }

val prepareBundledLauncherApk = tasks.register("prepareBundledLauncherApk") {
    val outDir = bundledAssetDir.get().asFile
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        val targetApk = File(outDir, "Astryx-Rust-Launcher-bundled.apk")
        val targetMeta = File(outDir, "bundled-version.txt")

        var versionLabel = "1.2.0"
        if (launcherGradleFile != null && launcherGradleFile.isFile) {
            val match = Regex("""versionName\s*=\s*"([^"]+)"""").find(launcherGradleFile.readText())
            if (match != null) versionLabel = match.groupValues[1]
        }

        val sourceApk: File? = when {
            launcherBuiltApk != null && launcherBuiltApk.isFile -> launcherBuiltApk
            else -> {
                val dlDir = File("$userHome/Downloads/Android Development")
                dlDir.listFiles { _, name ->
                    name.startsWith("Astryx-Rust-Launcher-") && name.endsWith(".apk")
                }?.maxByOrNull { it.lastModified() }
            }
        }

        if (sourceApk != null && sourceApk.isFile) {
            sourceApk.copyTo(targetApk, overwrite = true)
            targetMeta.writeText("v$versionLabel")
            logger.lifecycle("Bundled launcher APK: ${sourceApk.absolutePath} (v$versionLabel)")
        } else {
            targetApk.delete()
            targetMeta.delete()
            logger.lifecycle("No local launcher APK found to bundle; app will use GitHub Releases or local file picker.")
        }
    }
}

android {
    namespace = "com.astryxtv.installer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.astryxtv.installer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(bundledAssetDir)
        }
    }

    androidResources {
        // Keep the bundled APK stored uncompressed for instant extraction without double-deflate overhead.
        noCompress += listOf("apk")
    }

    signingConfigs {
        if (activeSigningDir != null) {
            create("burbsRelease") {
                storeFile = File(activeSigningDir, "burbsklopek.jks")
                val pass = File(activeSigningDir, "burbsklopek.jks.pass").readText().trim()
                storePassword = pass
                keyAlias = "burbsklopek"
                keyPassword = pass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (activeSigningDir != null) {
                signingConfigs.getByName("burbsRelease")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.named("preBuild") {
    dependsOn(prepareBundledLauncherApk)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
