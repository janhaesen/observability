import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))

    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.4.0")
    compileOnly("org.springframework.boot:spring-boot-actuator:3.4.0")
    compileOnly("io.micrometer:micrometer-core:1.14.0")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.0")

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.4.0")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator:3.4.0")
    testImplementation("io.micrometer:micrometer-core:1.14.0")
}

extensions.configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "observability-spring-boot-starter"
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
