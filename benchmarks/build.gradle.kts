plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
}

application {
    mainClass.set("io.github.aeshen.observability.benchmarks.SinkBackpressureBenchmarkKt")
}
