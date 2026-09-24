plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The public API is core:model types, Flows and ShotTransport (core:protocol).
            api(projects.core.model)
            api(projects.core.protocol)
            // SettingsRepository.units is a core:insights UnitSystem (plan R5a).
            api(projects.core.insights)
            implementation(projects.core.network)
            implementation(projects.core.ble)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.okio)
            // WifiShotTransport's constructor takes an HttpClient; core:network keeps Ktor as implementation.
            implementation(libs.ktor.client.core)
            // dataModule/platformDataModule are public Koin Modules.
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            // ShotRepositoryTest drives a real PiControlClient against a MockEngine; core:network's
            // installOpenFlightDefaults() is internal to that module, so the JSON plugins are
            // installed here directly instead.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
