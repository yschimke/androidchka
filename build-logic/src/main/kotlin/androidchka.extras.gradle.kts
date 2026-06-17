// User-editable convention plugin applied to every source project on top of the AndroidXPlugin
// shim. Edit freely — anything that's a valid Gradle Kotlin DSL build script works here.
//
// To add a plugin, edit *two* lines (Gradle limitation: precompiled script plugins forbid
// `version` in their plugins block; the version must be on the build-logic classpath):
//
//   1. In `build-logic/build.gradle.kts` add an `implementation` for the plugin marker:
//        implementation("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.15.13")
//   2. Below, declare the id (without a version) — `apply false` if you want to gate the
//      apply on per-project conditions.

plugins {
    // Stage the plugin classes on the project classpath without applying them — we
    // conditionally apply below.
    id("ee.schimke.composeai.preview") apply false
    id("com.gradleup.tapmoc") apply false
}

afterEvaluate {
    pluginManager.apply("com.gradleup.tapmoc")
    tapmoc {
        java(21)
    }
}

afterEvaluate {
    val subdirectory = path.replace(":", "/")
    val goldenDir = File(project.rootDir, "../androidx-main/golden$subdirectory")
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
            sourceSets.getByName("androidTest").assets.srcDir(project.files(goldenDir))
            sourceSets.getByName("test").assets.srcDir(project.files(goldenDir))
        }
    }
}

// Compose Preview: applied to every Android *source* module — the selection follows
// `androidx.sources` (local.properties.example), not a hardcoded module list. This convention
// plugin only ever runs on source projects (AndroidXPlugin applies it, and only a real upstream
// build.gradle pulls that in — overlay stubs have no build script), so changing androidx.sources
// changes the preview set automatically.
//
// We apply it *here*, from the convention plugin, rather than relying on the compose-preview CLI's
// `--init-script` auto-inject: in this overlay AGP is supplied by the `build-logic` included build,
// so an auto-injected plugin (on the project buildscript classpath) can't see AGP's
// `AndroidComponentsExtension` and every project fails to configure with NoClassDefFoundError.
// Staging the plugin on build-logic's classpath (the marker in build-logic/build.gradle.kts) puts
// it on the *same* classloader as AGP, which is what makes discovery/render work. The CLI's
// auto-inject detects this included-build provider and stays out of the way (compose-ai-tools#1939).
//
// Applied *eagerly* via `pluginManager.withPlugin(...)` (not `afterEvaluate {}`) so the apply lands
// inside the configuration window and the variant-backed render/discover tasks register; the plugin
// no-ops on modules with no `@Preview`s.
listOf("com.android.library", "com.android.application").forEach { agpId ->
    pluginManager.withPlugin(agpId) { pluginManager.apply("ee.schimke.composeai.preview") }
}
