plugins {
    alias(libs.plugins.openflight.kmp.library)
}

// core:speech has no core:* dependencies (plan F-series §1): a SpeechEngine only needs a Koin
// Module type and coroutines Flow/StateFlow, both of which are plain kotlinx types.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // speechModule/platformSpeechModule are public Koin Modules.
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
    }
}
