rootProject.name = "OpenFlight"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared")
include(":core:data")
include(":core:designsystem")
include(":core:flight")
include(":core:insights")
include(":core:model")
include(":core:network")
include(":core:protocol")
include(":core:ble")
include(":core:sensors")
include(":core:socketio")
include(":core:testing")
include(":feature:dashboard")
include(":feature:calibration")
include(":feature:range")
include(":feature:session")
include(":feature:training")
include(":feature:camera")
include(":feature:settings")
include(":feature:dashboard:ui")
include(":feature:calibration:ui")
include(":feature:range:ui")
