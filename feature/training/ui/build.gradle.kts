plugins {
    // ADR 0001: the Android (Jetpack Compose) UI for the shared TrainingViewModel (plan R6c).
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // Its own shared feature module plus core modules only, never another feature.
    implementation(projects.feature.training)
    implementation(projects.core.designsystem)
    implementation(projects.core.insights)
    implementation(projects.core.model)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
}
