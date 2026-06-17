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

// Compose Preview: applied to every Android *source* module — the selection is driven by
// `androidx.sources` (local.properties.example), not a hardcoded module list. This convention
// plugin only ever runs on source projects: it's applied by AndroidXPlugin (build-logic), which a
// project only gets from a real upstream build.gradle; the overlay's stubs have no build script,
// so they never reach here. Change `androidx.sources` and the preview set follows automatically.
//
// Applied *eagerly* via `pluginManager.withPlugin(...)` (not `afterEvaluate {}`): the plugin
// registers its render/discover tasks from an `androidComponents.onVariants {}` hook, which only
// fires if the plugin is applied inside the normal configuration window — applying from
// `afterEvaluate {}` runs after AGP locks the variants and the variant-backed tasks
// (`composePreviewRenderAll`/`composePreviewDiscover`) never register. Gated on the AGP plugins so
// only Android modules get it; the plugin further no-ops on modules with no `@Preview`s, so
// applying it across the source set is harmless.
listOf("com.android.library", "com.android.application").forEach { agpId ->
    pluginManager.withPlugin(agpId) { pluginManager.apply("ee.schimke.composeai.preview") }
}
