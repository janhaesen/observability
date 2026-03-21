plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
}

group = "io.github.aeshen"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    api(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

