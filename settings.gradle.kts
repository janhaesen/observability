rootProject.name = "observability"
include(":query-spi")
include(":benchmarks")
include(":examples:third-party-sink-example")
include(":sidecar")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
