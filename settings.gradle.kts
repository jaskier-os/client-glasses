pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        maven("https://alphacephei.com/maven/")
        maven("https://maven.rokid.com/repository/maven-public/")
    }
}

rootProject.name = "GlassesListener"
include(":app")
include(":bt-manager")
include(":capture")
include(":filesync")
include(":glasses-tracing")
