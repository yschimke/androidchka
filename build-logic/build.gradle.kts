import java.util.Properties

plugins {
    `kotlin-dsl`
}

/** Read a property from the overlay root's `gradle.properties` (one level above build-logic). */
fun overlayProperty(name: String): String {
    val props = Properties().apply {
        rootDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) }
    }
    return props.getProperty(name) ?: error("'$name' missing from overlay gradle.properties")
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(libs.androidGradlePluginApi)
    // Kotlin gradle plugin types are needed at compile time for the KMP shim and at runtime for
    // applying `org.jetbrains.kotlin.multiplatform` programmatically.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    // AGP KMP library plugin — provides the `androidLibrary` extension on the kotlin DSL.
    implementation(libs.androidKotlinMultiplatform)
    // The compose compiler plugin must be on this build-logic classpath at *runtime* so
    // `apply("org.jetbrains.kotlin.plugin.compose")` from AndroidXComposePlugin can find it.
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.composeCompilerPlugin.get()}")

    // --- Extras: plugin marker artifacts referenced from `androidchka.extras.gradle.kts`.
    // Versions live in the overlay's `gradle.properties` (build-logic is an included build, so
    // its own rootProject doesn't see the overlay's properties — we read them explicitly).
    implementation("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:${overlayProperty("composeAiPreviewVersion")}")
}

gradlePlugin {
    plugins {
        register("androidXPlugin") {
            id = "AndroidXPlugin"
            implementationClass = "androidx.build.AndroidXPlugin"
        }
        register("androidXComposePlugin") {
            id = "AndroidXComposePlugin"
            implementationClass = "androidx.build.AndroidXComposePlugin"
        }
    }
}
