plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.2"
}

// Only the SDK is a public API; the hub and the probe are apps.
apiValidation {
    ignoredProjects.addAll(listOf("hub", "probe"))
}
