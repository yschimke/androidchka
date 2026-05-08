plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // AGP API for our extension wiring; AGP itself comes via the consuming project.
    compileOnly(libs.androidGradlePluginApi)
    // The compose compiler plugin must be on this build-logic classpath at *runtime* so
    // `apply("org.jetbrains.kotlin.plugin.compose")` from AndroidXComposePlugin can find it.
    implementation(libs.composeCompilerGradlePlugin)
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
