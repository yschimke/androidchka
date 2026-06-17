// User-editable convention plugin applied to every source project on top of the AndroidXPlugin
// shim. Edit freely — anything that's a valid Gradle Kotlin DSL build script works here.
//
// To add a plugin, edit *two* lines (Gradle limitation: precompiled script plugins forbid
// `version` in their plugins block; the version must be on the build-logic classpath):
//
//   1. In `build-logic/build.gradle.kts` add an `implementation` for the plugin marker:
//        implementation("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.10.4")
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

/**
 * On by default: apply the Compose Preview plugin to every Android source project.
 *
 * This is applied *eagerly* (via [PluginManager.withPlugin], during configuration) rather than
 * from `afterEvaluate {}`. The Compose Preview plugin registers its render/discover tasks from an
 * `androidComponents.onVariants {}` hook; if the plugin is applied from inside `afterEvaluate {}`
 * that hook runs after the AGP variants are already locked, so only the variant-independent
 * marker tasks register and `composePreviewRenderAll` never appears. Applying on the
 * `com.android.library` / `com.android.application` plugin callback keeps the apply inside the
 * normal configuration window so the variant hooks land.
 *
 * The Compose Preview plugin no-ops on modules that have no `@Preview` functions, so applying it
 * to every Android module is harmless. To turn this off entirely, comment out the
 * `withPlugin(...)` calls below — the plugin is staged via `plugins { ... apply false }` above, so
 * removing the apply calls prevents it from running.
 */
listOf("com.android.library", "com.android.application").forEach { agpId ->
    pluginManager.withPlugin(agpId) { pluginManager.apply("ee.schimke.composeai.preview") }
}

// NOTE: Preview discovery under AGP 9.x built-in Kotlin (`built_in_kotlinc`) requires
// compose-preview >= 0.15.12; we pin 0.15.13 (see composeAiPreviewVersion in gradle.properties),
// which also carries the CLI-side fix for discovering previews when the plugin is applied via a
// convention plugin like this one (https://github.com/yschimke/compose-ai-tools/issues/1939).
// Earlier plugin versions discover 0 previews here: 0.15.9 scanned only legacy KGP class dirs;
// 0.15.12 added built-in-Kotlin support and canonical-path matching for symlinked build trees
// (androidchka's `androidx` symlink). https://github.com/yschimke/compose-ai-tools/issues/1924
