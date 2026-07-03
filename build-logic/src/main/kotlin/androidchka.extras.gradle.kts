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
    // Application modules (e.g. integration-test apps) carry their screenshot goldens in the
    // androidTest APK too — mirror upstream's addGoldenImageAssets() for them.
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension>("android") {
            sourceSets.getByName("androidTest").assets.srcDir(project.files(goldenDir))
            sourceSets.getByName("test").assets.srcDir(project.files(goldenDir))
        }
    }
}

// Robolectric APK-path fix. AGP writes module-relative paths into the generated
// `com.android.tools.test_config.properties` (android_resource_apk / android_merged_manifest /
// android_merged_assets), e.g. `../../../../build/androidx-builds/<flat>/intermediates/...`. Those
// resolve correctly only when the module's buildDir sits under its own directory. This overlay
// redirects buildDir out-of-tree (settings.gradle.kts -> build/androidx-builds/<flat>) and reaches
// source modules through a symlink; the test JVM's working dir is the symlinked module path, which
// the OS canonicalizes, so the `../../../../build` offset lands in the wrong tree and Robolectric
// fails every test with PackageParserException ("Failed to parse ...apk-for-local-test.ap_").
//
// Rewrite those relative values to absolute after AGP generates the file. We normalize lexically
// against the (symlinked) projectDir path via `Path.normalize()` — which strips `..` segments
// WITHOUT resolving symlinks (unlike File.getCanonicalPath()) — so the result points at the real
// redirected buildDir regardless of the test JVM's canonicalized working directory.
run {
    val projDirPath = projectDir.toPath()
    tasks.matching { it.name.matches(Regex("generate.*UnitTestConfig")) }.configureEach {
        doLast {
            outputs.files.asFileTree.filter { it.name == "test_config.properties" }.forEach { f ->
                val rewritten = f.readLines().joinToString("\n") { line ->
                    val eq = line.indexOf('=')
                    if (eq > 0 && line.substring(eq + 1).startsWith("../")) {
                        line.substring(0, eq + 1) +
                            projDirPath.resolve(line.substring(eq + 1)).normalize().toString()
                    } else {
                        line
                    }
                }
                f.writeText(rewritten + "\n")
            }
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
