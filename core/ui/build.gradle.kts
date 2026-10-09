plugins {
    // ADR 0001: Android-only Jetpack Compose. Domain-aware UI shared by feature:*:ui modules (the
    // shot detail sheet), built from core:designsystem's Of* wrappers; iOS mirrors it in SwiftUI.
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    implementation(projects.core.designsystem)
    // The sheet renders core:insights' ShotDetailState (and core:flight's projector, via its api).
    api(projects.core.insights)
    implementation(projects.core.model)
}
