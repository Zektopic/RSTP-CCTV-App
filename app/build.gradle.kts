plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zektopic.cctvapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.zektopic.cctvapp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("com.github.pedroSG94.RootEncoder:library:2.6.7")
    implementation("com.github.pedroSG94:RTSP-Server:master-SNAPSHOT")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}