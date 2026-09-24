plugins {
    alias(libs.plugins.openflight.kmp.library)
}

// Test doubles for the core:data repositories, shared by the feature modules' commonTest source
// sets (`commonTest.dependencies { implementation(projects.core.testing) }`). Never a main dependency.
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.data)
            api(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
