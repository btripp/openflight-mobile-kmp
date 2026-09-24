plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // Test-only: pins the wire payload that a calculator-produced measurement encodes to
            // (the ported PhoneOrientationTests request/command cases). Main code never needs it.
            implementation(projects.core.protocol)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
