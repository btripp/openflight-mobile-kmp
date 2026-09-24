plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The public API is core:model types, Flows and ShotTransport (core:protocol).
            api(projects.core.model)
            api(projects.core.protocol)
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
    }
}
