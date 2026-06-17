package androidx.build

import org.gradle.api.Project

/**
 * No-op stand-in for the upstream `androidx { }` extension. Captures every property and method
 * the in-tree build files use, but performs no publishing, sample wiring, golden-image setup,
 * or API-tracking work — those are intentionally out of scope for this slim overlay build.
 */
open class AndroidXExtension(internal val project: Project) {
    var name: String? = null
    var description: String? = null
    var inceptionYear: String? = null
    var type: SoftwareType = SoftwareType.UNSET
    var mavenVersion: Any? = null
    var legacyDisableKotlinStrictApiMode: Boolean = false
    var failOnDeprecationWarnings: Boolean = true
    var metalavaK2UastEnabled: Boolean = true
    var doNotDocumentReason: String? = null
    var kotlinTarget: Any? = null

    fun samples(@Suppress("UNUSED_PARAMETER") samplesProject: Project) { /* no-op */ }
    fun samples(@Suppress("UNUSED_PARAMETER") samplesProject: Any?) { /* no-op */ }
    fun addGoldenImageAssets() { /* no-op */ }
    fun deviceTests(@Suppress("UNUSED_PARAMETER") action: Any? = null) { /* no-op */ }
    fun enableRobolectric() {
        project.dependencies.add("testImplementation", "org.robolectric:robolectric:4.12.2")
    }
    fun useEmojiCompat() { /* no-op */ }
}
