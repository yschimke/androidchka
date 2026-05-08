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
    // Some upstream build files still use the legacy `id("kotlin")` shorthand. There's no
    // corresponding plugin marker artifact (`kotlin` isn't a real plugin id on the portal),
    // so route the request to `kotlin-gradle-plugin`, which contains the alias.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "kotlin") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
            }
        }
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

// Gradle implicitly materializes every parent of a nested path (`:wear:compose:remote:foo`
// pulls in `:wear`, `:wear:compose`, `:wear:compose:remote`) and demands each have a real
// projectDir. Walk every prefix; pin the leaf to its assigned dir, and pin parents to the
// matching `androidx/<path>` if it exists, else to a placeholder under `stubs/`.
fun assignProjectDirs(path: String, leafDir: File) {
    val segments = path.removePrefix(":").split(":")
    for ((i, _) in segments.withIndex()) {
        val currentPath = ":${segments.take(i + 1).joinToString(":")}"
        val proj = project(currentPath)
        val isLeaf = i == segments.size - 1
        if (isLeaf) {
            // Always pin the leaf — once a parent is set, Gradle resolves the leaf's default
            // relative to it, which can point at a real upstream project we explicitly want
            // stubbed (e.g. parent at androidx/benchmark + leaf default at
            // androidx/benchmark/benchmark-macro-junit4 with its own build.gradle).
            proj.projectDir = leafDir
        } else if (!proj.projectDir.isDirectory) {
            // Parent: first-write-wins, only assign if not already pointed at a real dir.
            val androidxDir = File(androidxRoot, segments.take(i + 1).joinToString("/"))
            proj.projectDir = if (androidxDir.isDirectory) androidxDir
                else file("stubs/${currentPath.removePrefix(":").replace(':', '-')}").apply { mkdirs() }
        }
    }
}

fun source(path: String, relativeProjectDir: String) {
    include(path)
    assignProjectDirs(path, File(androidxRoot, relativeProjectDir))
    sourceProjects += path
}

val skippedSources = linkedMapOf<String, String>()

// Plugins the overlay knows how to satisfy — either provided by build-logic, pinned in the
// pluginManagement.plugins block above, or wired via `androidchka.extras.gradle.kts`. Anything
// outside this set causes a project to be demoted to a stub.
val supportedPlugins: Set<String> = setOf(
    "AndroidXPlugin",
    "AndroidXComposePlugin",
    "com.android.library",
    "com.android.application",
    "org.jetbrains.kotlin.android",
    "org.jetbrains.kotlin.jvm",
    "kotlin", // alias for org.jetbrains.kotlin.jvm
    "org.jetbrains.kotlin.plugin.compose",
    "androidchka.extras",
    "ee.schimke.composeai.preview",
)
val pluginIdRegex = Regex("""\b(?:id|alias)\s*\(\s*["']([^"']+)["']""")
val unsupportedDslMarkers = mapOf<String, String>(
    // KMP is handled via AndroidXMultiplatformExtension (build-logic). Add markers here for any
    // other DSL we don't satisfy (e.g. AGP test plugin "com.android.test" handled by plugin-id
    // filter below).
)

/**
 * Parse upstream's `settings.gradle` once to map directory paths back to the canonical Gradle
 * project paths upstream uses (e.g. `wear/compose/remote/remote-material3/samples` is at
 * `:wear:compose:remote:remote-material3-samples`, not `:...:remote-material3:samples`). Used
 * by [expandSource] so recursive auto-discovery matches what `project(":...")` references in
 * upstream build files expect, and by [autoSourceForPath] to find directories of common
 * unpublished internals like `:internal-testutils-*`.
 */
val upstreamPathToDir: Map<String, String> = run {
    val upstreamSettings = File(androidxRoot, "settings.gradle")
    if (!upstreamSettings.isFile) return@run emptyMap()
    val withDir = Regex("""includeProject\(\s*["'](:[^"']+)["']\s*,\s*["']([^"']+)["']""")
    val noDir = Regex("""includeProject\(\s*["'](:[^"']+)["']\s*[,)]""")
    val text = upstreamSettings.readText()
    val map = LinkedHashMap<String, String>()
    // First pass: explicit dir mappings (paths whose dir doesn't follow the `:` -> `/` rule).
    withDir.findAll(text).forEach { map[it.groupValues[1]] = it.groupValues[2] }
    // Second pass: single-arg includes default to the path-derived directory.
    noDir.findAll(text).forEach { m ->
        val path = m.groupValues[1]
        map.getOrPut(path) { path.removePrefix(":").replace(':', '/') }
    }
    map
}
val upstreamDirToPath: Map<String, String> = upstreamPathToDir.entries.associate { it.value to it.key }

/**
 * Common upstream projects that are widely referenced as `project(":x")` deps but are *not
 * published* to androidx.dev (they're internal/test/sampled). Promoting them from stub to
 * source automatically saves users from having to enumerate them in `local.properties`.
 *
 * Additions need three properties: (1) referenced often, (2) unpublished (else a snapshot
 * substitution would just work), (3) builds with the plugins/DSL the overlay supports.
 */
val autoSourcePaths: Set<String> = setOf(
    // Trivial pure-JVM annotation provider; widely used by samples.
    ":annotation:annotation-sampled",
    // Internal test infrastructure — pure JVM/AGP, no exotic DSL, all `INTERNAL_TEST_LIBRARY`.
    ":internal-testutils-common",
    ":internal-testutils-runtime",
    ":internal-testutils-truth",
    ":internal-testutils-benchmark-macro",
    // `:compose:test-utils` and `:compose:benchmark-utils` are heavily referenced but use KMP
    // targets (`desktop()`, `androidHostTest`) the overlay's KMP shim doesn't fake yet — they
    // stay as snapshot-resolved stubs.
)

/**
 * Expands a source spec to one or more `source()` calls. If the target directory has its own
 * build script it's used directly; we still recurse into subdirectories so nested projects
 * (`samples`, etc.) get picked up. Each project's Gradle path is taken from upstream's
 * `settings.gradle` when available so references like `project(":wear:compose:remote:remote-material3-samples")`
 * resolve, with a fall-back to the directory-derived `:` path.
 *
 * Projects that reference DSL or plugin ids the overlay doesn't satisfy are demoted to stubs
 * so the rest of the build can still configure; they're surfaced in `skippedSources` and
 * logged once Gradle has the root project.
 */
fun expandSource(path: String, dir: File) {
    if (!dir.isDirectory) error("source path '$path' resolves to non-existent dir $dir")
    val buildScript = sequenceOf("build.gradle", "build.gradle.kts")
        .map { File(dir, it) }
        .firstOrNull { it.isFile }
    if (buildScript != null) {
        val canonicalPath = upstreamDirToPath[dir.relativeTo(androidxRoot).path] ?: path
        val text = buildScript.readText()
        val reason: String? =
            unsupportedDslMarkers.entries.firstOrNull { it.key in text }?.value
                ?: pluginIdRegex.findAll(text)
                    .map { it.groupValues[1] }
                    .firstOrNull { it !in supportedPlugins }
                    ?.let { "plugin: $it" }
        if (reason != null) {
            skippedSources[canonicalPath] = reason
            stub(canonicalPath)
        } else {
            source(canonicalPath, dir.relativeTo(androidxRoot).path)
        }
        // Fall through to recurse — subdirs may host nested projects (e.g. `samples`).
    }
    val children = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        .sortedBy { it.name }
    for (child in children) {
        expandSource("$path:${child.name}", child)
    }
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
    expandSource(path, File(androidxRoot, relativeDir))
}

// --- Stub projects: referenced by source projects but resolved to androidx.dev artifacts.
//
// Each stub must exist as a Gradle project so `project(":x")` lookups in upstream build.gradle
// files don't fail at configuration time. The AndroidXPlugin's `dependencySubstitution.all { }`
// rule then swaps the project dependency for a Maven coordinate at resolution time.
fun stub(path: String) {
    include(path)
    val dirName = path.removePrefix(":").replace(':', '-')
    val leafDir = file("stubs/$dirName").apply { mkdirs() }
    assignProjectDirs(path, leafDir)
}

// Discover referenced project paths by scanning each source's build.gradle. The regex covers
// both `project(":x")` and `project(path: ":x")` forms; line comments are stripped first to
// cut false positives.
val projectRefRegex = Regex("""project\s*\(\s*(?:path\s*[:=]\s*)?["'](:[A-Za-z0-9:_\-]+)["']""")
val lineCommentRegex = Regex("""(?m)//[^\n]*""")
val autoPromoted = linkedSetOf<String>()
fun collectReferenced(): Set<String> = buildSet {
    for (path in sourceProjects) {
        val projectDir = project(path).projectDir
        val gradleFile = sequenceOf("build.gradle.kts", "build.gradle")
            .map { File(projectDir, it) }
            .firstOrNull { it.isFile } ?: continue
        val source = gradleFile.readText().replace(lineCommentRegex, "")
        addAll(projectRefRegex.findAll(source).map { it.groupValues[1] })
    }
}

// Iterate to a fixpoint: a newly-promoted source can itself reference more `autoSourcePaths`
// entries that weren't visible yet. Two iterations is usually enough for the curated set.
while (true) {
    val referenced = collectReferenced()
    val toPromote = referenced.intersect(autoSourcePaths) - sourceProjects
    if (toPromote.isEmpty()) break
    for (path in toPromote.sorted()) {
        val relativeDir = upstreamPathToDir[path]
            ?: error("autoSourcePaths entry $path has no upstream directory mapping")
        expandSource(path, File(androidxRoot, relativeDir))
        autoPromoted += path
    }
}

// Whatever's still referenced but isn't a source becomes a stub.
val finalReferenced = collectReferenced()
val toStub = finalReferenced - sourceProjects
toStub.sorted().forEach(::stub)

gradle.rootProject {
    logger.lifecycle("[androidchka] sources (${sourceProjects.size}): ${sourceProjects.toList()}")
    if (autoPromoted.isNotEmpty()) {
        logger.lifecycle("[androidchka] auto-promoted (${autoPromoted.size}): ${autoPromoted.toList()}")
    }
    logger.lifecycle("[androidchka] auto-stubs (${toStub.size}): ${toStub.sorted()}")
    if (skippedSources.isNotEmpty()) {
        logger.lifecycle("[androidchka] skipped sources (${skippedSources.size}): " +
            skippedSources.entries.joinToString { "${it.key} (${it.value})" })
    }
}
