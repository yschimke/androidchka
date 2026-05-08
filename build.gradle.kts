// Root build script for the androidx-mini overlay. Per-project configuration is done by the
// AndroidXPlugin shim in build-logic/.

tasks.register("printSnapshot") {
    doLast {
        val props = java.util.Properties()
        rootProject.file("snapshots.properties").inputStream().use(props::load)
        println("androidx.dev snapshot build id: ${props.getProperty("androidxSnapshotBuildId")}")
    }
}
