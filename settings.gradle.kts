rootProject.name = "observability"
include(":query-spi")
include(":benchmarks")
include(":examples:third-party-sink-example")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
    }
}
