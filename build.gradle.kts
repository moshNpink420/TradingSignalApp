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
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("app/src/main/app/src/main/app/src/app/src/main/Androidmanifest.xml")
            java.srcDirs("app/src/main/app/src/main/app/src/app/src/main/app/src/main/java")
            res.srcDirs("app/src/main/app/src/main/app/src/app/src/main/app/src/main/res")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
