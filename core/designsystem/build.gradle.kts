plugins {
    // ADR 0001: Android-only Jetpack Compose. iOS builds its own SwiftUI components.
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // The Of* wrappers are the only place allowed to use Material3 (detekt ForbiddenImport).
    implementation(libs.androidx.compose.material3)
}
