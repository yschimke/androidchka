// Root build script for the androidx-mini overlay. Per-project configuration is done by the
// AndroidXPlugin shim in build-logic/.

// Note on the "Kotlin plugin loaded multiple times" warning:
// Our build-logic must depend on `kotlin-gradle-plugin` (we use KMP types in the AndroidX
// Multiplatform shim), and AGP 9 bundles its *own* Kotlin support that's not available as a
// standalone artifact. Pre-staging Kotlin plugins at the root project would normally unify
// classloaders, but doing so pulls in a kotlin-gradle-plugin copy that references removed AGP
// API (`BaseVariant`) and breaks AGP plugin application. The warning is benign in our setup.

tasks.register("printSnapshot") {
    doLast {
        val props = java.util.Properties()
        rootProject.file("snapshots.properties").inputStream().use(props::load)
        println("androidx.dev snapshot build id: ${props.getProperty("androidxSnapshotBuildId")}")
    }
}
