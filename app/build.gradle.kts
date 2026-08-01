plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.uniaball.uide"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uniaball.uide"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

    kotlinOptions {
        jvmTarget = "17"
        // Opt in to Material3 experimental APIs (e.g. TopAppBar) at module level.
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM pins all androidx.compose.* versions (Compose 1.7 line,
    // which pairs with navigation-compose 2.8.x below).
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // AndroidX (not in Compose BOM) — versioned explicitly.
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
