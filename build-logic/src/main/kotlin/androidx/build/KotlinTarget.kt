package androidx.build

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

enum class KotlinTarget(val apiVersion: KotlinVersion, val catalogVersion: String) {
    KOTLIN_2_1(KotlinVersion.KOTLIN_2_1, "kotlin21"),
    KOTLIN_2_2(KotlinVersion.KOTLIN_2_2, "kotlin22"),
    KOTLIN_2_3(KotlinVersion.KOTLIN_2_3, "kotlin23"),
    DEFAULT(KOTLIN_2_1),
    LATEST(KOTLIN_2_3);

    constructor(
        kotlinTarget: KotlinTarget
    ) : this(kotlinTarget.apiVersion, kotlinTarget.catalogVersion)
}