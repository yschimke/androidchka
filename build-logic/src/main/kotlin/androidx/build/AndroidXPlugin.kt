package androidx.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
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

    private fun composeVersion(project: Project): String {
        val file = project.rootProject.layout.projectDirectory.file("androidx/libraryversions.toml")
        val contentProvider = project.providers.fileContents(file).asText
        val text = contentProvider.orNull
        if (text != null) {
            val match = Regex("""COMPOSE\s*=\s*["']([^"']+)["']""").find(text)
            if (match != null) {
                val version = match.groupValues[1]
                val baseVersion = version.substringBefore("-")
                return "$baseVersion-SNAPSHOT"
            }
        }
        return "1.0.0-SNAPSHOT"
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
                            val version = if (coords.group.startsWith("androidx.compose") && !coords.group.startsWith("androidx.compose.remote")) {
                                composeVersion(project)
                            } else {
                                "1.0.0-SNAPSHOT"
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
