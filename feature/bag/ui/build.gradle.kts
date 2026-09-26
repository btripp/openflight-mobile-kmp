plugins {
    // ADR 0001: the Android (Jetpack Compose) UI for the shared bag ViewModels (plan F5).
    alias(libs.plugins.openflight.android.library.compose)
}

dependencies {
    // Its own shared feature module plus core modules only, never another feature.
    implementation(projects.feature.bag)
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.insights)
    implementation(projects.core.model)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // BackHandler: back closes the club detail before leaving My Bag on one pane.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.androidx.compose)
    // The device tests drive the real BagViewModel over these fakes.
    androidTestImplementation(projects.core.testing)
}
