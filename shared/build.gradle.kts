plugins {
    // No Compose (ADR 0001): the common app bootstrap for both platforms, and the iOS umbrella
    // framework `Shared` that the SwiftUI app links.
    alias(libs.plugins.openflight.kmp.library)
    // Swift interop for Flow/suspend (ADR 0001, "Decision (R2)"): @NativeCoroutines* in iosMain.
    alias(libs.plugins.kmp.nativecoroutines)
}

kotlin {
    // Static: the app links the Kotlin code into its own binary, so there is no dylib to embed or
    // sign, launch is faster, and the one framework is the only Kotlin/Native runtime in the app.
    // `embedAndSignAppleFrameworkForXcode` handles static frameworks too.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            freeCompilerArgs += "-Xbinary=bundleId=dev.openflight.companion.Shared"
            // What Swift sees by name (everything else it only sees as types it can't create).
            export(projects.core.model)
            export(projects.core.data)
            export(projects.core.insights)
            export(projects.core.flight)
            // Plan F7: the voice picker needs Voice/VoiceQuality from Swift.
            export(projects.core.speech)
            export(projects.feature.dashboard)
            export(projects.feature.calibration)
            export(projects.feature.range)
            export(projects.feature.session)
            export(projects.feature.training)
            export(projects.feature.camera)
            export(projects.feature.settings)
            export(projects.feature.bag)
        }
    }

    sourceSets {
        all { languageSettings.optIn("kotlin.experimental.ExperimentalObjCName") }
        commonMain.dependencies {
            // `api`: the Android app starts Koin with these modules, and the iOS framework
            // exports them (export() needs an api dependency).
            api(projects.core.model)
            api(projects.core.data)
            api(projects.core.insights)
            // The range scene (camera, markers, trees, tracer style) is built in Swift from these.
            api(projects.core.flight)
            api(projects.feature.dashboard)
            api(projects.feature.calibration)
            api(projects.feature.range)
            api(projects.feature.session)
            api(projects.feature.training)
            api(projects.feature.camera)
            api(projects.feature.settings)
            // Plan F5: My Bag, club analysis and gapping.
            api(projects.feature.bag)
            // Plan F4: core:speech's speechModule joins the app's Koin graph below. Plan F7 exports
            // it above too: the settings voice picker needs Voice/VoiceQuality from Swift.
            api(projects.core.speech)
            implementation(libs.kotlinx.coroutines.core)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        // Plan F7: fakes for ShotCalloutCoordinatorTest (FinalShotStream, SpeechEngine, ...).
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}
