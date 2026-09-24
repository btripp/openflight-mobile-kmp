import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "dev.openflight.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // compileOnly: the root build.gradle.kts puts the real plugins on the classpath
    // (`apply false`), so every module shares one copy.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.composeCompilerGradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}

tasks {
    validatePlugins {
        enableStricterValidation = true
        failOnWarning = true
    }
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id =
                libs.plugins.openflight.kmp.library
                    .get()
                    .pluginId
            implementationClass = "dev.openflight.buildlogic.KmpLibraryConventionPlugin"
        }
        register("androidApplication") {
            id =
                libs.plugins.openflight.android.application
                    .get()
                    .pluginId
            implementationClass = "dev.openflight.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("spotless") {
            id =
                libs.plugins.openflight.spotless
                    .get()
                    .pluginId
            implementationClass = "dev.openflight.buildlogic.SpotlessConventionPlugin"
        }
        register("detekt") {
            id =
                libs.plugins.openflight.detekt
                    .get()
                    .pluginId
            implementationClass = "dev.openflight.buildlogic.DetektConventionPlugin"
        }
        register("androidLibraryCompose") {
            id =
                libs.plugins.openflight.android.library.compose
                    .get()
                    .pluginId
            implementationClass = "dev.openflight.buildlogic.AndroidLibraryComposeConventionPlugin"
        }
    }
}
