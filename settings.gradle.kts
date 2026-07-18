rootProject.name = "observability"
include(":query-spi")
include(":benchmarks")
include(":examples:third-party-sink-example")
include(":sidecar")
include(":sidecar-client-jvm")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
