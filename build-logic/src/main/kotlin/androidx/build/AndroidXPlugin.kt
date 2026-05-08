package androidx.build

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ProjectComponentSelector
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
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

        // The `plugins {}` block in upstream build files applies `AndroidXPlugin` before
        // `com.android.library`, so configure AGP lazily once it shows up. AGP 9.x bundles
        // Kotlin support so we do *not* re-apply the Kotlin plugin here — doing so collides
        // on the `kotlin` extension.
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.configure<LibraryExtension>("android") {
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
    private fun rootFile(project: Project, name: String): File =
        project.rootProject.layout.projectDirectory.file(name).asFile

    private fun loadProps(project: Project): Properties {
        val f = rootFile(project, "snapshots.properties")
        val p = Properties()
        if (f.isFile) f.inputStream().use(p::load)
        return p
    }

    /**
     * Hand-curated overrides for paths whose Maven coordinates don't follow the standard
     * `:a:b:c` → `androidx.a.b:c` rule (e.g. samples artifacts published under flattened names).
     */
    private val overrides: Map<String, String> = mapOf(
        ":wear:compose:remote:remote-material3-samples" to
            "androidx.wear.compose:compose-remote-material3-samples",
    )

    fun snapshotId(project: Project): String =
        loadProps(project).getProperty("androidxSnapshotBuildId")
            ?: error("androidxSnapshotBuildId missing from snapshots.properties")

    /**
     * Default convention: `:foo:bar:my-lib` becomes `androidx.foo.bar:my-lib`. The artifact id is
     * the trailing segment; the group prepends `androidx.` to the dot-joined leading segments.
     */
    private fun coordinateFor(path: String): String {
        overrides[path]?.let { return it }
        val segments = path.removePrefix(":").split(":")
        require(segments.size >= 2) { "Cannot derive maven coords from project path '$path'" }
        val artifact = segments.last()
        val group = "androidx." + segments.dropLast(1).joinToString(".")
        return "$group:$artifact"
    }

    /**
     * A project is "overlay stub" if its directory lives under `stubs/` at the overlay root —
     * that's the convention used by [Settings.kt][settings.gradle.kts]'s `stub()` helper.
     */
    private fun isStub(project: Project): Boolean {
        val stubsDir = rootFile(project.rootProject, "stubs")
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
                            val module = "${coordinateFor(selector.projectPath)}:1.0.0-SNAPSHOT"
                            useTarget(module, "overlay: stub -> androidx.dev snapshot $buildId")
                        }
                    }
                }
            }
        }
    }
}
