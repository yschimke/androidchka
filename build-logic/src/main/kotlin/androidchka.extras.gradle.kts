// User-editable convention plugin applied to every source project on top of the AndroidXPlugin
// shim. Edit freely — anything that's a valid Gradle Kotlin DSL build script works here.
//
// To add a plugin, edit *two* lines (Gradle limitation: precompiled script plugins forbid
// `version` in their plugins block; the version must be on the build-logic classpath):
//
//   1. In `build-logic/build.gradle.kts` add an `implementation` for the plugin marker:
//        implementation("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.10.4")
//   2. Below, add the id to this `plugins { }` block (without a version):
//        id("ee.schimke.composeai.preview")
//
// To restrict to a subset of projects, drop the `plugins { }` form and use conditional apply:
//   if (project.path == ":wear:compose:remote:remote-material3") {
//       apply(plugin = "ee.schimke.composeai.preview")
//   }

plugins {
    id("ee.schimke.composeai.preview")
}
