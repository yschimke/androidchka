package androidx.build

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet

/**
 * Slim replacement for the upstream `androidXMultiplatform { }` DSL. Wires up:
 *  - `org.jetbrains.kotlin.multiplatform` (always)
 *  - `com.android.kotlin.multiplatform.library` (lazily on `androidLibrary { }`)
 *  - the upstream `jvmAndAndroidMain` source-set hierarchy node, so build files can declare
 *    deps shared between the jvm and android targets without further wiring.
 *
 * Anything beyond what the in-tree `compose:remote:remote-creation` build.gradle calls is out
 * of scope (no native targets, no klib publishing, no test source-set hierarchy beyond the
 * Kotlin defaults).
 */
open class AndroidXMultiplatformExtension(internal val project: Project) {
    // Lazy: registering the extension shouldn't pull in Kotlin Multiplatform on projects that
    // never actually call into it (most of them).
    private var kmpApplied = false
    private fun ensureKmpApplied() {
        if (kmpApplied) return
        kmpApplied = true
        project.pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    }

    private val kotlin: KotlinMultiplatformExtension
        get() {
            ensureKmpApplied()
            return project.extensions.getByType()
        }

    /**
     * Apply the AGP Kotlin Multiplatform Android library plugin and configure its target.
     * Mirrors `androidXMultiplatform { androidLibrary { ... } }`.
     */
    fun androidLibrary(block: Closure<*>) {
        ensureKmpApplied()
        project.pluginManager.apply("com.android.kotlin.multiplatform.library")
        val target = (kotlin as ExtensionAware).extensions.getByType(
            KotlinMultiplatformAndroidLibraryTarget::class.java
        )
        configure(target, block)
        // Auto-enable host & device test compilations when the matching source dirs exist on
        // disk — upstream's projects expect `androidUnitTest` / `androidDeviceTest` source sets
        // to materialize without an explicit `withHostTest {}` / `withDeviceTest {}` call.
        if (java.io.File(project.projectDir, "src/androidUnitTest").isDirectory) {
            target.withHostTest { }
        }
        if (java.io.File(project.projectDir, "src/androidDeviceTest").isDirectory) {
            target.withDeviceTest { }
        }
        scheduleHierarchyWiring()
    }

    /** Add a JVM target. Mirrors upstream `androidXMultiplatform { jvm() }`. */
    fun jvm() {
        kotlin.jvm()
        scheduleHierarchyWiring()
    }

    fun jvm(block: Closure<*>) {
        kotlin.jvm { configure(this, block) }
        scheduleHierarchyWiring()
    }

    /** No-op: upstream uses this to pick a publication's "default" platform; not needed here. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultPlatform(id: PlatformIdentifier) { /* no-op */ }

    /**
     * Delegate to the kotlin extension's source sets. The `jvmAndAndroidMain` intermediate node
     * is materialized eagerly so build files can reference it inside the `sourceSets { }`
     * closure; the `dependsOn` wiring runs later once both targets exist.
     */
    fun sourceSets(block: Closure<*>) {
        kotlin.sourceSets.maybeCreate("jvmAndAndroidMain")
        scheduleHierarchyWiring()
        configure(kotlin.sourceSets, block)
    }

    private fun configure(target: Any, block: Closure<*>) {
        block.delegate = target
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.call()
    }

    private var hierarchyScheduled = false
    private fun scheduleHierarchyWiring() {
        if (hierarchyScheduled) return
        hierarchyScheduled = true
        // Wire after both targets exist; safest spot is before configurations resolve.
        @Suppress("DEPRECATION")
        project.afterEvaluate {
            val ss = kotlin.sourceSets
            val common = ss.findByName("commonMain") ?: return@afterEvaluate
            val jvm = ss.findByName("jvmMain")
            val android = ss.findByName("androidMain")
            if (jvm == null && android == null) return@afterEvaluate
            val intermediate: KotlinSourceSet = ss.maybeCreate("jvmAndAndroidMain")
            intermediate.dependsOn(common)
            jvm?.dependsOn(intermediate)
            android?.dependsOn(intermediate)
        }
    }
}

/** Subset of upstream's `PlatformIdentifier` — only enough for build files to compile. */
enum class PlatformIdentifier {
    JVM, JVM_STUBS, JS, WASM_JS, ANDROID,
    ANDROID_NATIVE_ARM32, ANDROID_NATIVE_ARM64, ANDROID_NATIVE_X86, ANDROID_NATIVE_X64,
    MAC_ARM_64, MINGW_X_64, LINUX_ARM_64, LINUX_X_64, LINUX_X_64_STUBS,
    IOS_SIMULATOR_ARM_64, IOS_ARM_64,
    WATCHOS_SIMULATOR_ARM_64, WATCHOS_ARM_32, WATCHOS_ARM_64, WATCHOS_DEVICE_ARM_64,
    DESKTOP,
}
