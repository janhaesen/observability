plugins {
    kotlin("jvm") version "2.3.20"
    application
}

group = "io.github.aeshen"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
}

application {
    mainClass.set("io.github.aeshen.observability.benchmarks.SinkBackpressureBenchmarkKt")
}

