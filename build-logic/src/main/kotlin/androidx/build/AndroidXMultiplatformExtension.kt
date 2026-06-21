package androidx.build

import com.android.build.api.dsl.KotlinMultiplatformAndroidCompilation
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

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

        // Customize the source-set hierarchy: insert `jvmAndAndroidMain` between `commonMain`
        // and the `jvmMain` / `androidMain` leaves so build.gradle files can declare deps shared
        // by jvm and android targets. `applyDefaultHierarchyTemplate` extends Kotlin's default
        // wiring with our group; manual `dependsOn` calls would disable the default template and
        // emit a warning. Match the AGP KMP target via `withCompilations { it is
        // KotlinMultiplatformAndroidCompilation }` per upstream's workaround for b/442950553.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        kotlin.applyDefaultHierarchyTemplate {
            common {
                group("jvmAndAndroid") {
                    withJvm()
                    withCompilations { it is KotlinMultiplatformAndroidCompilation }
                }
                // `nonJvmMain` (and its `web` subgroup for js/wasmJs) holds code shared by the
                // non-JVM targets, which can't use the Java `remote-core`. Native targets, when
                // declared, also fall under `nonJvm`.
                group("nonJvm") {
                    withNative()
                    group("web") {
                        withJs()
                        withWasmJs()
                    }
                }
            }
        }
        kotlin.sourceSets.maybeCreate("nonJvmMain")
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
        target.compileSdk = 37
        configure(target, block)
        // Auto-enable host & device test compilations when the matching source dirs exist on
        // disk — upstream's projects expect `androidUnitTest` / `androidDeviceTest` source sets
        // to materialize without an explicit `withHostTest {}` / `withDeviceTest {}` call.
        if (!project.fileTree("src/androidUnitTest").isEmpty || !project.fileTree("src/androidHostTest").isEmpty) {
            target.withHostTest { }
            kotlin.sourceSets.maybeCreate("androidHostTest")
        }
        if (!project.fileTree("src/androidDeviceTest").isEmpty) {
            target.withDeviceTest { }
        }
    }

    /** Add a JVM target. Mirrors upstream `androidXMultiplatform { jvm() }`. */
    fun jvm() {
        kotlin.jvm()
    }

    fun mac() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun linux() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun ios() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun watchos() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun tvos() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun androidNative() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun mingwX64() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("nativeMain")
        kotlin.sourceSets.maybeCreate("nativeTest")
    }

    fun jvmStubs() {
    }

    fun linuxX64Stubs() {
    }

    fun desktop() {
        ensureKmpApplied()
        kotlin.sourceSets.maybeCreate("desktopMain")
        kotlin.sourceSets.maybeCreate("desktopTest")
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalWasmDsl::class)
    fun wasmJs() {
        ensureKmpApplied()
        kotlin.wasmJs { browser() }
    }

    fun js() {
        ensureKmpApplied()
        kotlin.js { browser() }
    }

    fun jvm(block: Closure<*>) {
        kotlin.jvm { configure(this, block) }
    }

    /** No-op: upstream uses this to pick a publication's "default" platform; not needed here. */
    @Suppress("UNUSED_PARAMETER")
    fun defaultPlatform(id: PlatformIdentifier) { /* no-op */ }

    /** Delegate to the kotlin extension's source sets. The `jvmAndAndroid` group is registered
     *  by the hierarchy template applied in [ensureKmpApplied], so `jvmAndAndroidMain` exists
     *  by the time this closure executes. */
    fun sourceSets(block: Closure<*>) {
        configure(kotlin.sourceSets, block)
    }

    fun createNativeCompilation(
        name: String,
        type: Any,
        block: Closure<*>
    ) {
        // no-op
    }

    fun addNativeLibrariesToVariantAssets(
        target: Any?,
        nativeCompilation: Any?
    ) {
        // no-op
    }

    private fun configure(target: Any, block: Closure<*>) {
        block.delegate = target
        block.resolveStrategy = Closure.DELEGATE_FIRST
        block.call()
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
