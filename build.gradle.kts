plugins {
    // Load every plugin once in the root classloader so subprojects share it.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kmp.nativecoroutines) apply false
    id("openflight.spotless")
}
