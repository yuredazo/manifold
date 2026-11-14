import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    `maven-publish`
}

group = "dev.mkzk.manifold"
version = "1.1.0"

android {
    namespace = "dev.mkzk.manifold.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Everything public is a decision: visibility and types have to be written out.
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Optional listener callbacks are real default methods for Java callers too.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                artifactId = "manifold-sdk"
                pom {
                    name.set("Manifold SDK")
                    description.set("Send and receive live video and audio between Android apps through the Manifold hub.")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
    }
}
