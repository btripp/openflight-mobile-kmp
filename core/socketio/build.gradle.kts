plugins {
    alias(libs.plugins.openflight.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // SocketEvent carries kotlinx JsonElement args; the Ktor transport takes an HttpClient.
            // Ktor 3.6's client WebSockets plugin ships in ktor-client-core (the separate
            // ktor-client-websockets artifact is an empty shell); OkHttp and Darwin both support it.
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            // Plan R8d: every URL passes core:network's EndpointPolicy before Ktor opens it.
            implementation(projects.core.network)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
        }
    }
}
