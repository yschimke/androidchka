import java.util.Properties

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

/** Read a property from the overlay root's `gradle.properties` (one level above build-logic). */
fun overlayProperty(name: String, default: String? = null): String {
    val props = Properties().apply {
        rootDir.parentFile.resolve("gradle.properties").inputStream().use { load(it) }
    }
    return providers.systemProperty(name)
        .orElse(providers.gradleProperty(name))
        .orElse(props.getProperty(name) ?: default ?: error("'$name' missing from overlay gradle.properties"))
        .get()
}

val androidchkaAgpVersion = overlayProperty("androidchka.agpVersion", "9.3.0-alpha01")

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly("com.android.tools.build:gradle-api:$androidchkaAgpVersion")
    // Kotlin gradle plugin types are needed at compile time for the KMP shim and at runtime for
    // applying `org.jetbrains.kotlin.multiplatform` programmatically.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    // AGP KMP library plugin — provides the `androidLibrary` extension on the kotlin DSL.
    implementation(
        "com.android.kotlin.multiplatform.library:" +
            "com.android.kotlin.multiplatform.library.gradle.plugin:$androidchkaAgpVersion"
    )
    // The compose compiler plugin must be on this build-logic classpath at *runtime* so
    // `apply("org.jetbrains.kotlin.plugin.compose")` from AndroidXComposePlugin can find it.
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:${libs.versions.composeCompilerPlugin.get()}")

    // --- Extras: plugin marker artifacts referenced from `androidchka.extras.gradle.kts`.
    // Versions live in the overlay's `gradle.properties` (build-logic is an included build, so
    // its own rootProject doesn't see the overlay's properties — we read them explicitly).
    implementation("com.gradleup.tapmoc:tapmoc-gradle-plugin:0.4.2")
    implementation("com.google.protobuf:protobuf-gradle-plugin:0.9.4")
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
