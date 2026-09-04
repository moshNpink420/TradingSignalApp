plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moshnpink420.tradingsignalapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moshnpink420.tradingsignalapp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val twelveDataApiKey =
            System.getenv("TWELVE_DATA_API_KEY") ?: ""

        buildConfigField(
            "String",
            "TWELVE_DATA_API_KEY",
            "\"$twelveDataApiKey\""
        )
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
