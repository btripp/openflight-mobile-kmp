plugins {
    alias(libs.plugins.openflight.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Plan F6 (§1): internet data, outside the Pi's `EndpointPolicy` (that gates LAN hosts only).
// Every request goes to a single hardcoded https host (Open-Meteo), never a user-supplied one,
// so this module needs no allow-list of its own beyond "https, and only that host".
kotlin {
    sourceSets {
        commonMain.dependencies {
            // WeatherObservation.wind reuses core:model's Wind (speedMps/fromDegrees) rather than
            // inventing a second wind type that ConditionsRepository would have to convert between.
            api(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
