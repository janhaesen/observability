plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
    `maven-publish`
}

group = "io.github.aeshen" // A company name, for example, `org.jetbrains`
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
    implementation("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
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
