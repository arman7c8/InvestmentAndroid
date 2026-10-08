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
        versionCode = 32
        versionName = "0.32.0"
    }

    // CI-only side-load preview: one isolated applicationId per source commit.
    // Do NOT change the regular applicationId or any production user data.
    // Separate IDs avoid update-signature conflicts with existing installations.
    buildTypes {
        getByName("debug") {
            val previewSuffix = providers.gradleProperty("previewApplicationSuffix").orNull
            if (previewSuffix != null) {
                require(Regex("preview[a-f0-9]{12}").matches(previewSuffix)) {
                    "Invalid previewApplicationSuffix; expected preview + 12 hex digits."
                }
                applicationIdSuffix = ".$previewSuffix"
                manifestPlaceholders["appDisplayName"] = "Investment Preview"
            }
        }
    }

    defaultConfig {
        manifestPlaceholders["appDisplayName"] = "Investment Android"
    }

    buildFeatures {
        buildConfig = true
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
