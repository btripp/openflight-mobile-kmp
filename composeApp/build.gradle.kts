plugins {
    alias(libs.plugins.openflight.kmp.compose)
    // Typed navigation routes are @Serializable objects.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            freeCompilerArgs += "-Xbinary=bundleId=dev.openflight.companion.ComposeApp"
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` so the Android app module can start Koin with these modules.
            api(projects.core.data)
            api(projects.feature.dashboard)
            api(projects.feature.calibration)
            api(projects.feature.range)
            implementation(projects.core.designsystem)
            implementation(projects.core.model)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jetbrains.lifecycle.viewmodelCompose)
            implementation(libs.jetbrains.lifecycle.runtimeCompose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose)
        }
        androidMain.dependencies {
            // requiredBluetoothPermissions for the runtime permission prompt.
            implementation(projects.core.ble)
            implementation(libs.androidx.activity.compose)
        }
    }
}
