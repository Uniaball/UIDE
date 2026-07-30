// Root build file: declares plugin versions only.
// NOTE: if AGP rejects `compileSdk = 36`, bump the `com.android.application`
// version below to the latest 8.x that supports Android 16 (API 36).
plugins {
    id("com.android.application") version "8.9.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}
