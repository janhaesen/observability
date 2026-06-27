rootProject.name = "observability"
include(":query-spi")
include(":benchmarks")
include(":examples:third-party-sink-example")
include(":observability-spring-boot-starter")

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
