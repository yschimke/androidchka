@file:Suppress("UnstableApiUsage")

import java.util.Properties

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Pin the versions of plugins referenced by upstream `build.gradle` files (which use the
    // versionless `id("...")` form). build-logic ids are supplied by the included build itself.
    plugins {
        id("com.android.library") version "9.3.0-alpha01"
        id("com.android.application") version "9.3.0-alpha01"
        id("org.jetbrains.kotlin.android") version "2.3.20"
        id("org.jetbrains.kotlin.jvm") version "2.3.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    }
}

val snapshotProps = Properties().apply {
    file("snapshots.properties").inputStream().use(::load)
}
val snapshotId: String = snapshotProps.getProperty("androidxSnapshotBuildId")
    ?: error("androidxSnapshotBuildId missing from snapshots.properties")

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven("https://androidx.dev/snapshots/builds/$snapshotId/artifacts/repository") {
            name = "androidx-snapshot"
            content { includeGroupByRegex("androidx\\..*") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "androidx-mini"

// --- Source projects: built from the upstream androidx checkout (via symlink) ---
val androidxRoot: File = file("androidx")

fun source(path: String, relativeProjectDir: String) {
    include(path)
    project(path).projectDir = File(androidxRoot, relativeProjectDir)
}

source(":wear:compose:remote:remote-material3", "wear/compose/remote/remote-material3")
source(":compose:remote:remote-creation-compose", "compose/remote/remote-creation-compose")

// --- Stub projects: referenced by the source projects but resolved to androidx.dev artifacts.
//     Each must exist as a Gradle project so `project(":x")` lookups in build.gradle don't fail;
//     `substitutions.properties` swaps the project dependency for a Maven coordinate at
//     resolution time. Keep this list aligned with substitutions.properties.
//
// Gradle implicitly materializes every parent of a nested path (`:test:screenshot:screenshot`
// pulls in `:test` and `:test:screenshot`) and demands each have a real projectDir, so we walk
// every prefix and assign it a placeholder dir under stubs/.
fun stub(path: String) {
    include(path)
    val segments = path.removePrefix(":").split(":")
    var current = ""
    for (segment in segments) {
        current = if (current.isEmpty()) ":$segment" else "$current:$segment"
        val dirName = current.removePrefix(":").replace(':', '-')
        val dir = file("stubs/$dirName").apply { mkdirs() }
        project(current).projectDir = dir
    }
}

stub(":compose:remote:remote-creation")
stub(":compose:remote:remote-core")
stub(":compose:remote:remote-core-testutils")
stub(":compose:remote:remote-player-core")
stub(":compose:remote:remote-player-compose")
stub(":compose:remote:remote-player-compose-testutils")
stub(":compose:remote:remote-player-view")
stub(":compose:test-utils")
stub(":compose:ui:ui-test")
stub(":compose:ui:ui-test-junit4")
stub(":test:screenshot:screenshot")
stub(":test:uiautomator:uiautomator")
stub(":test:uiautomator:uiautomator-shell")
stub(":wear:compose:remote:remote-material3-samples")
