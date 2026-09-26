plugins {
    alias(libs.plugins.openflight.kmp.library)
    // A finished game's players and result go to ActivityRepository as JSON (plan F9).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A feature depends on core modules only, never on another feature.
            implementation(projects.core.data)
            // Offline distance, apex and total (est.) aren't on the wire: each shot is flown.
            implementation(projects.core.flight)
            implementation(projects.core.insights)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Shared presentation (ADR 0001): the KMP ViewModel base class, no Compose.
            api(libs.androidx.lifecycle.viewmodel)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.core.viewmodel)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
