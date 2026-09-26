plugins {
    alias(libs.plugins.openflight.kmp.library)
}

// Plan F6 (§1): `core:location` never depends on `core:data`. Its public API is `expect`/`actual`
// free except for the two platform factory functions; Android's needs a `Context`.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
        }
    }
}
