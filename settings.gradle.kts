rootProject.name = "observability"
include(":benchmarks")
include(":examples:third-party-sink-example")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
    }
}
