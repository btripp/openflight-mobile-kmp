import dev.openflight.buildlogic.commonTestJsonFixtures

plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // BleShotTransport's public API is ShotTransport (core:protocol) over core:model types.
            api(projects.core.model)
            api(projects.core.protocol)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Kable stays an implementation detail: no Kable type is part of this module's API.
            implementation(libs.kable.core)
        }
    }
}

// Plan R8e: the backend's BLE goldens, shared with core:protocol (see its fixture README).
commonTestJsonFixtures(
    fixtureDir = "../protocol/src/commonTest/fixtures/openflight-ble",
    packageName = "dev.openflight.companion.core.ble",
    objectName = "BleContractFixtures",
)
