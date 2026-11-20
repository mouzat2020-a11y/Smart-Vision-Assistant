plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mostafa.smartvision"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.mostafa.smartvision"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-alpha"
    }
}
