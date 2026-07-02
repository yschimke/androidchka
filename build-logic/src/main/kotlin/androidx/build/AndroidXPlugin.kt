package androidx.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import com.google.protobuf.gradle.*
import java.util.Properties

/**
 * Slim replacement for the upstream `AndroidXPlugin`. Applies the Kotlin Android plugin, registers
 * the `androidx { }` extension, plugs in repositories pointing at the configured androidx.dev
 * snapshot, and substitutes project dependencies on out-of-overlay androidx modules with their
 * snapshot Maven coordinates.
 *
 * Anything beyond compiling source and producing a debug AAR (api lockdown, lint, publishing,
 * samples integration, golden-image plumbing, KMP setup, …) is dropped. Reach for the upstream
 * build when you need those.
 */
class AndroidXPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create<AndroidXExtension>("androidx", project)
        project.extensions.create<AndroidXMultiplatformExtension>("androidXMultiplatform", project)
        project.extensions.create("lint", LintStub::class.java)

        project.plugins.withId("com.google.protobuf") {
            project.extensions.configure<com.google.protobuf.gradle.ProtobufExtension>("protobuf") {
                protoc {
                    artifact = "com.google.protobuf:protoc:3.25.1"
                }
                generateProtoTasks {
                    all().forEach { task ->
                        task.builtins {
                            getByName("java") {
                                option("lite")
                            }
                        }
                    }
                }
            }
        }

        project.plugins.withType(com.android.build.gradle.api.AndroidBasePlugin::class.java) {
            project.extensions.configure<com.android.build.api.dsl.CommonExtension>("android") {
                lint.abortOnError = false
            }
        }

        // Claim the canonical Maven coordinates for this project so a downstream `includeBuild`
        // can auto-substitute `androidx.<group>:<artifact>:<version>` to this project (Gradle
        // matches by group:name).
        val coords = SnapshotConfig.coordinatesFor(project.path)
        project.group = coords.group
        project.version = "1.0.0-SNAPSHOT"

        // The `plugins {}` block in upstream build files applies `AndroidXPlugin` before
        // `com.android.library`, so configure AGP lazily once it shows up. AGP 9.x bundles
        // Kotlin support so we do *not* re-apply the Kotlin plugin here — doing so collides
        // on the `kotlin` extension.
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.configure<LibraryExtension>("android") {
                compileSdk = 37
                compileOptions {
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
                }
                defaultConfig {
                    if (testInstrumentationRunner == null) {
                        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                }
            }
        }
        project.tasks.withType(KotlinCompile::class.java).configureEach {
            compilerOptions {
                freeCompilerArgs.add("-Xjvm-default=all")
            }
        }

        // Repositories are declared in settings.gradle.kts under PREFER_SETTINGS — adding them
        // again here would trigger a "repository was added by plugin" warning/error.
        SnapshotConfig.applyProjectSubstitutions(project)

        // User-editable convention plugin for any extra Gradle config (additional plugins,
        // dependencies, task tweaks). Applied to every source project; see
        // build-logic/src/main/kotlin/androidchka.extras.gradle.kts.
        project.pluginManager.apply("androidchka.extras")
    }
}

/**
 * Loads `snapshots.properties` and exposes the configured androidx.dev build id plus the
 * project-path → Maven-coordinate substitution that swaps stub projects for snapshot artifacts.
 */
internal object SnapshotConfig {
    /**
     * Hand-curated overrides for paths whose Maven coordinates don't follow the standard
     * `:a:b:c` → `androidx.a.b:c` rule (e.g. samples artifacts published under flattened names).
     */
    private val overrides: Map<String, String> = mapOf(
        ":wear:compose:remote:remote-material3-samples" to
            "androidx.wear.compose:compose-remote-material3-samples",
    )

    fun snapshotId(project: Project): String {
        val file = project.rootProject.layout.projectDirectory.file("snapshots.properties")
        val contentProvider = project.providers.fileContents(file).asText
        val content = contentProvider.orNull ?: error("No snapshots.properties found")
        val props = Properties().apply { load(content.reader()) }
        return props.getProperty("androidxSnapshotBuildId") ?: error("No androidxSnapshotBuildId in snapshots.properties")
    }

    data class Coordinates(val group: String, val artifact: String) {
        override fun toString() = "$group:$artifact"
    }

    /**
     * Default convention: `:foo:bar:my-lib` becomes `androidx.foo.bar:my-lib`. The artifact id is
     * the trailing segment; the group prepends `androidx.` to the dot-joined leading segments.
     */
    fun coordinatesFor(path: String): Coordinates {
        overrides[path]?.let {
            val (g, a) = it.split(":", limit = 2)
            return Coordinates(g, a)
        }
        val segments = path.removePrefix(":").split(":")
        require(segments.size >= 2) { "Cannot derive maven coords from project path '$path'" }
        return Coordinates(
            group = "androidx." + segments.dropLast(1).joinToString("."),
            artifact = segments.last(),
        )
    }

    private fun libraryVersionsToml(project: Project): String? {
        val file = project.rootProject.layout.projectDirectory.file("androidx/libraryversions.toml")
        return project.providers.fileContents(file).asText.orNull
    }

    /** Look up a `KEY = "x.y.z-..."` entry in the `[versions]` table. */
    private fun versionEntry(toml: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}\s*=\s*["']([^"']+)["']""")
            .find(toml)?.groupValues?.get(1)

    /** Strip a release-channel suffix to the base x.y.z and append `-SNAPSHOT`. */
    private fun baseSnapshot(version: String): String = "${version.substringBefore("-")}-SNAPSHOT"

    private fun composeVersion(project: Project): String =
        libraryVersionsToml(project)?.let { versionEntry(it, "COMPOSE") }?.let(::baseSnapshot)
            ?: "1.0.0-SNAPSHOT"

    /**
     * Resolve the snapshot version for an androidx group. Libraries are versioned independently —
     * e.g. `androidx.compose.material3` tracks `COMPOSE_MATERIAL3` (1.5.0-…), not the core
     * `COMPOSE` (1.13.0-…), and `androidx.concurrent` tracks `FUTURES` (1.4.0-…) — and the
     * snapshot repo only publishes each artifact under its own version. Honor the group's
     * `atomicGroupVersion` from libraryversions.toml; compose groups without one share the core
     * COMPOSE version (ui/foundation/runtime/animation). Anything unresolved falls back to
     * `1.0.0-SNAPSHOT`.
     */
    private fun groupVersion(project: Project, group: String): String {
        val toml = libraryVersionsToml(project) ?: return "1.0.0-SNAPSHOT"
        val groupLine = toml.lineSequence().firstOrNull {
            it.contains("group = \"$group\"") && it.contains("atomicGroupVersion")
        }
        val key = groupLine?.let {
            Regex("""atomicGroupVersion\s*=\s*["']versions\.([A-Za-z0-9_]+)["']""")
                .find(it)?.groupValues?.get(1)
        }
        val version = key?.let { versionEntry(toml, it) }
            ?: if (group.startsWith("androidx.compose")) versionEntry(toml, "COMPOSE") else null
        return version?.let(::baseSnapshot) ?: "1.0.0-SNAPSHOT"
    }

    private fun coordinateFor(path: String): String = coordinatesFor(path).toString()

    /**
     * A project is "overlay stub" if its directory lives under `stubs/` at the overlay root —
     * that's the convention used by [Settings.kt][settings.gradle.kts]'s `stub()` helper.
     */
    private fun isStub(project: Project): Boolean {
        val stubsDir = project.rootProject.layout.projectDirectory.dir("stubs").asFile
        return generateSequence(project.projectDir) { it.parentFile }
            .any { it == stubsDir }
    }

    fun applyProjectSubstitutions(project: Project) {
        val buildId = snapshotId(project)
        project.configurations.all {
            resolutionStrategy.dependencySubstitution {
                // Pattern from the Gradle docs: rewrite every project-typed dependency whose
                // target project is a stub overlay directory into the corresponding androidx.dev
                // snapshot module — no static lookup table needed.
                all {
                    val selector = requested
                    if (selector is ProjectComponentSelector) {
                        val target = project.rootProject.findProject(selector.projectPath)
                        if (target != null && isStub(target)) {
                            val coords = coordinatesFor(selector.projectPath)
                            // compose.remote groups are the overlay's own source projects (pinned
                            // to 1.0.0-SNAPSHOT for substitution); everything else resolves its
                            // real snapshot version from libraryversions.toml.
                            val version = if (coords.group.startsWith("androidx.compose.remote")) {
                                "1.0.0-SNAPSHOT"
                            } else {
                                groupVersion(project, coords.group)
                            }
                            val module = "$coords:$version"
                            useTarget(module, "overlay: stub -> androidx.dev snapshot $buildId")
                        }
                    }
                }
            }
        }
    }
}

open class LintStub {
    fun lint(block: groovy.lang.Closure<*>) {
        // no-op
    }
}
