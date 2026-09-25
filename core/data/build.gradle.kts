import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.openflight.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The public API is core:model types, Flows and ShotTransport (core:protocol).
            api(projects.core.model)
            api(projects.core.protocol)
            // SettingsRepository.units is a core:insights UnitSystem (plan R5a).
            api(projects.core.insights)
            implementation(projects.core.network)
            implementation(projects.core.ble)
            implementation(projects.core.socketio)
            // R8h: the Room shot history; its types stay behind ShotHistoryRepository.
            implementation(projects.core.database)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.okio)
            // WifiShotTransport's constructor takes an HttpClient; core:network keeps Ktor as implementation.
            implementation(libs.ktor.client.core)
            // dataModule/platformDataModule are public Koin Modules.
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            // ShotRepositoryTest drives a real PiControlClient against a MockEngine; core:network's
            // installOpenFlightDefaults() is internal to that module, so the JSON plugins are
            // installed here directly instead.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Plan R8g: MockServerIT drives this module's real repositories against a live
// `openflight-server --mock`. It lives in androidHostTest, the one JVM source set that sees
// core:data's internals and the Android actuals of Ktor, DataStore and Room. It runs only through
// its own `mockServerIT` task, so `allTests` and `check` never start a Python server.
// AGP creates the host test task late, so both hooks look it up lazily.
val hostTestName = "testAndroidHostTest"
tasks.withType<Test>().matching { it.name == hostTestName }.configureEach {
    filter.excludeTestsMatching("*.MockServerIT")
}

val backendDir: Provider<String> =
    providers
        .gradleProperty("openflight.backendDir")
        .orElse(providers.environmentVariable("OPENFLIGHT_BACKEND_DIR"))

tasks.register<Test>("mockServerIT") {
    group = "verification"
    description = "Runs MockServerIT against `openflight-server --mock` from OPENFLIGHT_BACKEND_DIR."
    val source = tasks.named<Test>(hostTestName).get()
    testClassesDirs = source.testClassesDirs
    classpath = source.classpath
    // The bundled-SQLite natives (in-memory Room) and the rest of the host tests' JVM setup.
    dependsOn(source.dependsOn)
    jvmArgumentProviders.addAll(source.jvmArgumentProviders)
    systemProperties(source.systemProperties)
    filter.includeTestsMatching("*.MockServerIT")
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    val dir = backendDir.orNull
    if (dir != null) systemProperty("openflight.backendDir", dir)
    onlyIf("OPENFLIGHT_BACKEND_DIR or -Popenflight.backendDir is set") {
        if (dir == null) {
            logger.lifecycle(
                "mockServerIT skipped: set OPENFLIGHT_BACKEND_DIR (or -Popenflight.backendDir) to an " +
                    "openflight backend checkout",
            )
        }
        dir != null
    }
}
