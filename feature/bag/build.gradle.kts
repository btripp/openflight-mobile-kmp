plugins {
    alias(libs.plugins.openflight.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A feature depends on core modules only, never on another feature (plan F5).
            implementation(projects.core.data)
            // Carry/total adjusted for conditions (ShotDistanceEstimator) and flight measurements.
            implementation(projects.core.flight)
            implementation(projects.core.insights)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
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
