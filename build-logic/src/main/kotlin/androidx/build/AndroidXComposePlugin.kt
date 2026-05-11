package androidx.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

class AndroidXComposePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Defer until AGP (which bundles Kotlin in 9.x) shows up; the compose plugin needs the
        // Kotlin compilations created by AGP.
        project.pluginManager.withPlugin("com.android.library") {
            project.extensions.configure<com.android.build.api.dsl.LibraryExtension>("android") {
                buildFeatures.compose = true
            }
        }
        project.plugins.apply("org.jetbrains.kotlin.plugin.compose")

        project.plugins.withId("org.jetbrains.kotlin.plugin.compose") {
            project.tasks.withType(KotlinCompilationTask::class.java).configureEach {
                val classpath = project.configurations.getByName("kotlinCompilerPluginClasspath")
                if (this is AbstractKotlinCompile<*>) {
                    pluginClasspath.from(classpath)
                }
            }
        }
    }
}
