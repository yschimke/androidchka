import org.gradle.api.artifacts.ProjectDependency

// User-editable convention plugin applied to every source project on top of the AndroidXPlugin
// shim. Edit freely — anything that's a valid Gradle Kotlin DSL build script works here.
//
// To add a plugin, edit *two* lines (Gradle limitation: precompiled script plugins forbid
// `version` in their plugins block; the version must be on the build-logic classpath):
//
//   1. In `build-logic/build.gradle.kts` add an `implementation` for the plugin marker:
//        implementation("xxx:xxx.gradle.plugin:0.10.4")
//   2. Below, declare the id (without a version) — `apply false` if you want to gate the
//      apply on per-project conditions.

plugins {
    // Stage the plugin classes on the project classpath without applying them — we
    // conditionally apply below.
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
