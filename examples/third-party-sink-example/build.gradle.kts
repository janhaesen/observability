plugins {
    kotlin("jvm") version "2.3.20"
}

group = "io.github.aeshen.examples"
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":")))
}
