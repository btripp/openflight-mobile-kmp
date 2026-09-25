import dev.openflight.buildlogic.commonTestJsonFixtures

plugins {
    alias(libs.plugins.openflight.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// Plan R8e: the backend's BLE goldens (copied files, see the fixture README) as a commonTest object.
commonTestJsonFixtures(
    fixtureDir = "src/commonTest/fixtures/openflight-ble",
    packageName = "dev.openflight.companion.core.protocol",
    objectName = "BleContractFixtures",
)
