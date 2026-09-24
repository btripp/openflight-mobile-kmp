plugins {
    alias(libs.plugins.openflight.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A feature depends on core modules only, never on another feature.
            implementation(projects.core.data)
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(projects.core.sensors)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.lifecycle.viewmodelCompose)
            implementation(libs.jetbrains.lifecycle.runtimeCompose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            // platformCalibrationModule builds the Android GravitySensor from androidContext().
            implementation(libs.koin.android)
        }
    }
}
