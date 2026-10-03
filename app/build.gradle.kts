plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uniaball.uide"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.uniaball.uide"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Minification and R8/ProGuard are not enabled for this lightweight project.
            // To enable, uncomment the lines below and review proguard-rules.pro.
            // isMinifyEnabled = true
            // proguardFiles(
            //     getDefaultProguardFile("proguard-android-optimize.txt"),
            //     "proguard-rules.pro"
            // )
        }
    }

    // ---- Release signing (CI only) ----
    // Activates only when KEYSTORE_BASE64 is provided (set from a repo secret in
    // .github/workflows/release.yml). Local/dev builds and the CI `build` job are
    // unaffected because the env var is absent, so release stays unsigned locally.
    val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
    if (!keystoreBase64.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file("$rootDir/keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
        buildTypes {
            release {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            // LD_PRELOAD has to point at a real file on disk, so the exec hook
            // (libuidexec.so) must be extracted into nativeLibraryDir instead of
            // being loaded straight out of the APK.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/**")
        }
    }

    androidResources {
        // The bootstrap asset is already a gzip stream; storing it verbatim
        // avoids a pointless deflate pass at package time.
        noCompress += "bin"
    }
}

/*
 * The exec self-test binary has to end up inside the app data directory (not in
 * nativeLibraryDir) so that the W^X exec path can be verified on a real device.
 * It is therefore published as a generated asset instead of a jniLib.
 */
abstract class CollectSelfTestAsset : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val binaries: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun collect() {
        val found = binaries.files.sortedBy { it.name }
        val selftest = found.firstOrNull { it.name == "uidexec-selftest" }
            ?: throw GradleException(
                "uidexec-selftest was not found under .cxx/ - has the native build run?",
            )
        val probe = found.firstOrNull { it.name == "uidexec-probe" }
        val target = outputDirectory.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        selftest.copyTo(File(target, CollectSelfTestAsset.ASSET_NAME), overwrite = true)
        probe?.copyTo(File(target, PROBE_ASSET_NAME), overwrite = true)
    }

    companion object {
        const val ASSET_NAME = "uidexec-selftest-arm64"
        const val PROBE_ASSET_NAME = "uidexec-probe-arm64"
    }
}

val collectSelfTestAsset = tasks.register<CollectSelfTestAsset>("uideCollectSelfTestAsset") {
    dependsOn("externalNativeBuildDebug")
    binaries.from(
        layout.projectDirectory.dir(".cxx").asFileTree.matching {
            include("**/uidexec-selftest", "**/uidexec-probe")
        },
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(collectSelfTestAsset) {
            it.outputDirectory
        }
    }
}

dependencies {
    // Compose BOM pins all androidx.compose.* versions (Compose 1.7 line,
    // which pairs with navigation-compose 2.8.x below).
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // AndroidX (not in Compose BOM) - versioned explicitly.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
