plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.model)
            // Plan F5 (A13): offline distance flies the shot; core:flight depends on core:model only.
            api(projects.core.flight)
        }
    }
}
