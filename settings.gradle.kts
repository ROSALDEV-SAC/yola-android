pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YOLA"
include(":app")
