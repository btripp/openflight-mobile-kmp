plugins {
    // Matches the other feature modules' convention plugin; this module's commonMain is
    // ViewModel-only (plan R5a: "containing only a commonMain VM"), with no @Composable code of
    // its own -- the native R5b UI lands in this module's androidMain/iosMain later.
    alias(libs.plugins.openflight.kmp.compose)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // A feature depends on core modules only, never on another feature.
            implementation(projects.core.data)
            implementation(projects.core.insights)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jetbrains.lifecycle.viewmodelCompose)
            implementation(libs.jetbrains.lifecycle.runtimeCompose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
