plugins {
    alias(libs.plugins.openflight.kmp.compose)
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
            implementation(libs.jetbrains.lifecycle.viewmodelCompose)
            implementation(libs.jetbrains.lifecycle.runtimeCompose)
        }
    }
}
