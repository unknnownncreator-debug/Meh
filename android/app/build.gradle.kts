plugins {
    id("com.android.application")
}

android {
    namespace = "app.meh"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.meh"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.17"
    }

    // A fixed signing key kept in the repo so every build can install over the last one.
    signingConfigs {
        create("meh") {
            storeFile = file("meh.keystore")
            storePassword = "mehmeh"
            keyAlias = "meh"
            keyPassword = "mehmeh"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("meh")
        }
        debug {
            signingConfig = signingConfigs.getByName("meh")
        }
    }

    // The ad blocker's domain list is shared with the desktop version (repo-level data/).
    sourceSets {
        getByName("main") { assets.srcDir("../../data") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Back-compatible Back handling (predictive back on new Android, key events on old).
    implementation("androidx.activity:activity:1.13.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
