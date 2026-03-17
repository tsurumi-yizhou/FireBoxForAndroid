plugins {
    id("com.android.library")
    id("kotlin-parcelize")
}

android {
    namespace = "com.firebox.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmToolchain(21)
        }
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
