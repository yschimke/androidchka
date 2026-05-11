package androidx.build

import org.gradle.api.Project

object BundleInsideHelper {
    @JvmStatic
    fun forInsideAar(
        project: Project,
        from: String,
        to: String,
        dropResourcesWithSuffix: String? = null
    ) {
        val bundleInside = project.configurations.maybeCreate("bundleInside")
        project.configurations.getByName("implementation").extendsFrom(bundleInside)
    }
}
