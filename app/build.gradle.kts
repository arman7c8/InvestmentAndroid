plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arman.investmentandroid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arman.investmentandroid"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "0.30.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
