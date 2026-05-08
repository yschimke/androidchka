package androidx.build

import org.gradle.api.Plugin
import org.gradle.api.Project

class AndroidXComposePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Defer until AGP (which bundles Kotlin in 9.x) shows up; the compose plugin needs the
        // Kotlin compilations created by AGP.
        project.pluginManager.withPlugin("com.android.library") {
            project.plugins.apply("org.jetbrains.kotlin.plugin.compose")
        }
    }
}
