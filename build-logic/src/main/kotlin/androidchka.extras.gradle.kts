import org.gradle.api.artifacts.ProjectDependency

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

/**
 * On by default: apply the Compose Preview plugin to every source project that pulls in
 * `androidx.compose.ui:ui-tooling` (as either an external module dep or an in-source
 * `project(...)` dep). Skips projects that won't have any @Preview functions to render
 * anyway. To turn this off entirely, comment out the `afterEvaluate { ... }` block — the
 * plugin is staged via `plugins { ... apply false }` above, so removing the apply call here
 * prevents it from running.
 */
afterEvaluate {
    fun hasUiTooling(): Boolean {
        for (cfgName in listOf("api", "implementation", "debugImplementation")) {
            val cfg = configurations.findByName(cfgName) ?: continue
            for (dep in cfg.dependencies) {
                val matches = when {
                    dep is ProjectDependency -> dep.name == "ui-tooling"
                    else -> dep.group == "androidx.compose.ui" && dep.name == "ui-tooling"
                }
                if (matches) return true
            }
        }
        return false
    }
    if (hasUiTooling()) {
        pluginManager.apply("ee.schimke.composeai.preview")
    }
}
