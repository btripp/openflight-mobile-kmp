plugins {
    alias(libs.plugins.openflight.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: the pi.* Socket.IO models keep hardware-specific blobs as JsonElement.
            api(libs.kotlinx.serialization.json)
        }
    }
}
