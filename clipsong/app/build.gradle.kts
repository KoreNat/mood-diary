plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.clipsong"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.example.clipsong"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
