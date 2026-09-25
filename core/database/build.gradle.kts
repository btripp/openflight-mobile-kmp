plugins {
    // openflight.kmp.library + KSP + the Room 3 Gradle plugin (schemas in ./schemas).
    alias(libs.plugins.openflight.kmp.room)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
