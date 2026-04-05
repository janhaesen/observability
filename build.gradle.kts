import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    kotlin("jvm") version "2.3.20"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

val openTelemetryVersion = "1.49.0"

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("VERSION_NAME").get()

repositories {
    mavenCentral()
}

detekt {
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    autoCorrect = false
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
}

configure<KtlintExtension> {
    version.set("1.2.1")
}

tasks.matching { it.name == "check" }.configureEach {
    dependsOn("ktlintCheck")
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension> {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        allRules = false
        autoCorrect = false
        parallel = true
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
    }

    extensions.configure<KtlintExtension> {
        version.set("1.2.1")
    }

    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("ktlintCheck")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    compileOnly("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    compileOnly("io.opentelemetry:opentelemetry-sdk:$openTelemetryVersion")
    compileOnly("io.opentelemetry:opentelemetry-exporter-otlp:$openTelemetryVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    compileOnly("org.apache.kafka:kafka-clients:3.9.0")
    compileOnly("software.amazon.awssdk:s3:2.29.0")
    compileOnly("io.lettuce:lettuce-core:6.3.2.RELEASE")

    testImplementation(kotlin("test"))
    testImplementation(testFixtures(project(":")))
    testImplementation("io.opentelemetry:opentelemetry-api:$openTelemetryVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk:$openTelemetryVersion")
    testImplementation("io.opentelemetry:opentelemetry-exporter-otlp:$openTelemetryVersion")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:$openTelemetryVersion")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-reload4j:2.0.17")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.networknt:json-schema-validator:1.5.8")
    testImplementation("org.apache.kafka:kafka-clients:3.9.0")
    testImplementation("software.amazon.awssdk:s3:2.29.0")
    testImplementation("software.amazon.awssdk:url-connection-client:2.29.0")
    testImplementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    testFixturesApi(kotlin("test"))
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            // Optional but recommended for clarity
            artifactId = "observability"
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/janhaesen/observability")

            credentials {
                username = findProperty("gpr.user") as String?
                    ?: System.getenv("GITHUB_USERNAME")
                password = findProperty("gpr.key") as String?
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
