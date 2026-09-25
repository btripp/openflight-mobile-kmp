plugins {
    alias(libs.plugins.openflight.android.application)
    // Typed navigation routes are @Serializable objects.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.openflight.companion"

    defaultConfig {
        applicationId = "dev.openflight.companion"
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    // Shared (KMP): Koin bootstrap, launch options, and the feature ViewModels.
    implementation(projects.shared)
    // Android UI (Jetpack Compose), one module per feature.
    implementation(projects.feature.dashboard.ui)
    implementation(projects.feature.calibration.ui)
    implementation(projects.feature.range.ui)
    implementation(projects.feature.session.ui)
    implementation(projects.feature.training.ui)
    implementation(projects.feature.camera.ui)
    implementation(projects.feature.settings.ui)
    implementation(projects.core.designsystem)
    // requiredBluetoothPermissions for the runtime permission prompt.
    implementation(projects.core.ble)
    implementation(libs.androidx.activity.compose)
    // FileProvider: the CSV export is shared from the cache dir (R5b).
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // R8d: ProcessLifecycleOwner feeds the shared foreground/background connection policy.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
}
