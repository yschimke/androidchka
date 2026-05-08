plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    compileOnly(libs.androidGradlePluginApi)
    // The compose compiler plugin must be on this build-logic classpath at *runtime* so
    // `apply("org.jetbrains.kotlin.plugin.compose")` from AndroidXComposePlugin can find it.
    implementation(libs.composeCompilerGradlePlugin)

    // --- Extras: plugin marker artifacts referenced from `androidchka.extras.gradle.kts`.
    // Pin one line per plugin (`<id>:<id>.gradle.plugin:<version>`); the precompiled script
    // plugin then refers to the id only.
    implementation("ee.schimke.composeai.preview:ee.schimke.composeai.preview.gradle.plugin:0.10.4")
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
