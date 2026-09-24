plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
        }
    }
}
