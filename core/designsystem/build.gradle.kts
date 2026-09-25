plugins {
    // ADR 0001: Android-only Jetpack Compose. iOS builds its own SwiftUI components.
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // The Of* wrappers are the only place allowed to use Material3 (detekt ForbiddenImport).
    implementation(libs.androidx.compose.material3)
    // F1a tablet support: window size classes (currentWindowAdaptiveInfo) and the navigation suite
    // (bottom bar on phones, rail on tablets) behind OfWindowClass and OfAdaptiveScaffold.
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
}
