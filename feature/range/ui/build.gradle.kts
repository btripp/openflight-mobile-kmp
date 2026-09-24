plugins {
    // ADR 0001: the Android (Jetpack Compose) UI for the shared DrivingRangeViewModel: the
    // Canvas renderer. The projection math it draws with stays in :feature:range (commonMain).
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // Its own shared feature module plus core modules only, never another feature.
    implementation(projects.feature.range)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.flight)
    implementation(projects.core.model)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
    // The route's device tests drive a real DrivingRangeViewModel over these fakes.
    androidTestImplementation(projects.core.testing)
}
