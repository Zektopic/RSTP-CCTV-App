import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

/**
 * Release signing credentials, resolved in priority order:
 *   1. `keystore.properties` in the repo root (git-ignored) -- for local release builds
 *   2. `RELEASE_KEYSTORE_*` environment variables -- for CI
 *   3. nothing -- the release build is then left UNSIGNED
 *
 * The keystore itself is never committed. It lives outside the repository and is
 * injected into CI from the `RELEASE_KEYSTORE_BASE64` secret. Forks and pull-request
 * builds have no credentials and fall through to (3) rather than failing.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey))?.takeIf { it.isNotEmpty() }

val releaseStorePath = signingValue("storeFile", "RELEASE_KEYSTORE_PATH")
val releaseStorePassword = signingValue("storePassword", "RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")

val hasReleaseSigning = releaseStorePath != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStorePath).exists()

android {
    namespace = "com.zektopic.cctvapp"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.zektopic.cctvapp"
        minSdk = 24
        targetSdk = 36
        // CI overrides these (-PversionCode from the run number, -PversionName from the
        // tag). Android refuses to install a build whose versionCode has not increased,
        // so a pinned versionCode made every published release un-updatable.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Kept off deliberately: TensorFlow Lite Task Vision and RootEncoder both
            // resolve classes reflectively, and a mis-shrunk release only fails at
            // runtime. Enabling R8 needs a full on-device pass first -- see README roadmap.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "No release signing credentials found -- the release APK will be UNSIGNED. " +
                        "Set them in keystore.properties or RELEASE_KEYSTORE_* environment variables."
                )
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }

    // Workaround for third-party JNI libs that are not yet 16KB page aligned.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    testImplementation(libs.junit)
    // android.jar's org.json is a stub that throws on every call. Supplying the real
    // implementation lets EventStore and DetectionEvent be tested on the JVM.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // NOTE: these two must stay in lockstep. RTSP-Server pins a specific RootEncoder
    // version transitively, and a mismatch resolves to whatever is newer.
    // Never use `master-SNAPSHOT` here: it is a moving target that silently changed the
    // resolved RootEncoder to 2.8.0 (which demands compileSdk 37) and broke the build
    // on a commit whose CI had previously passed.
    implementation(libs.rootencoder.library)
    implementation(libs.rtsp.server)
    implementation(libs.nanohttpd)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.mlkit.genai.image.description)
}