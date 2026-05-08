@file:Suppress("UnstableApiUsage")

import java.util.Properties

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Pin versions of plugins referenced by upstream `build.gradle` files (versionless ids in
    // their plugins blocks). build-logic ids are supplied by the included build itself.
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
        gradlePluginPortal()
    }
}

rootProject.name = "androidx-mini"

// --- Source projects: built from the upstream androidx checkout (via symlink) ---
val androidxRoot: File = file("androidx")
val sourceProjects = linkedSetOf<String>()

fun source(path: String, relativeProjectDir: String) {
    include(path)
    project(path).projectDir = File(androidxRoot, relativeProjectDir)
    sourceProjects += path
}

// `androidx.sources` from local.properties (per-clone, git-ignored), falling back to
// local.properties.example (committed defaults). Comma-separated paths; `:path = relative/dir`
// overrides the default `:` -> `/` mapping for an entry.
val configFile = sequenceOf("local.properties", "local.properties.example")
    .map(::file)
    .firstOrNull { it.isFile }
    ?: error("Neither local.properties nor local.properties.example found")
val configProps = Properties().apply { configFile.inputStream().use(::load) }
val sourceSpec = configProps.getProperty("androidx.sources")
    ?: error("`androidx.sources` not set in $configFile")

sourceSpec.split(",").map(String::trim).filter(String::isNotEmpty).forEach { entry ->
    val eqIdx = entry.indexOf('=')
    val path = if (eqIdx >= 0) entry.substring(0, eqIdx).trim() else entry
    val relativeDir = if (eqIdx >= 0) entry.substring(eqIdx + 1).trim()
                      else path.removePrefix(":").replace(':', '/')
    source(path, relativeDir)
}

// --- Stub projects: referenced by source projects but resolved to androidx.dev artifacts.
//
// Each stub must exist as a Gradle project so `project(":x")` lookups in upstream build.gradle
// files don't fail at configuration time. The AndroidXPlugin's `dependencySubstitution.all { }`
// rule then swaps the project dependency for a Maven coordinate at resolution time.
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

// Auto-populate stubs by scanning each source project's build.gradle for `project(":path")`
// references. The regex covers both `project(":x")` and `project(path: ":x")` forms with
// either quote style. Matches inside line comments are stripped first to cut false positives;
// block comments are rare enough in androidx build files that we don't bother.
val projectRefRegex = Regex("""project\s*\(\s*(?:path\s*[:=]\s*)?["'](:[A-Za-z0-9:_\-]+)["']""")
val lineCommentRegex = Regex("""(?m)//[^\n]*""")
val referenced = linkedSetOf<String>()
for (path in sourceProjects) {
    val projectDir = project(path).projectDir
    val gradleFile = sequenceOf("build.gradle.kts", "build.gradle")
        .map { File(projectDir, it) }
        .firstOrNull { it.isFile } ?: continue
    val source = gradleFile.readText().replace(lineCommentRegex, "")
    referenced += projectRefRegex.findAll(source).map { it.groupValues[1] }
}
val toStub = referenced - sourceProjects
toStub.sorted().forEach(::stub)
gradle.rootProject {
    logger.lifecycle("[androidchka] auto-stubs: ${toStub.size} -> ${toStub.sorted()}")
}
