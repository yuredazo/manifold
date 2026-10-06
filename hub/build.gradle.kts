import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreProperties = rootProject.file("keystore.properties")

android {
    namespace = "dev.mkzk.manifold.hub"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mkzk.manifold"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.1"
    }

    signingConfigs {
        if (keystoreProperties.exists()) {
            create("release") {
                val keys = Properties().apply { keystoreProperties.inputStream().use(::load) }
                storeFile = rootProject.file(keys.getProperty("storeFile"))
                storePassword = keys.getProperty("storePassword")
                keyAlias = keys.getProperty("keyAlias")
                keyPassword = keys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Contributors without the release key still get an installable build.
            // Anything that is published must come from a build that has the key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("keystore.properties not found: the release build is signed with the debug key")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // BouncyCastle and jspecify both ship this OSGi file; Android never reads it.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sdk"))

    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Lightweight API only (X25519 and ChaCha20-Poly1305), so it works on every supported Android version.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")

    testImplementation("junit:junit:4.13.2")
    // The android.jar used by unit tests only has stubs for org.json.
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
