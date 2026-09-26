plugins {
    // ADR 0001: the Android (Jetpack Compose) UI for the shared SettingsViewModel (plan R6c).
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // Its own shared feature module plus core modules only, never another feature.
    implementation(projects.feature.settings)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.insights)
    implementation(projects.core.model)
    // Plan F7: Voice/VoiceQuality for the voice picker.
    implementation(projects.core.speech)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
}
