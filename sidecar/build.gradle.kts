plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("org.openapi.generator")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("io.github.aeshen.observability.sidecar.MainKt")
}

openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("$projectDir/src/main/openapi/sidecar-v1.yaml")
    outputDir.set("$rootDir/.openapi-generated/sidecar")
    modelPackage.set("io.github.aeshen.observability.sidecar.api.model")
    globalProperties.set(mapOf("models" to "", "apis" to "false", "supportingFiles" to "false"))
    configOptions.set(
        mapOf(
            "library" to "jvm-ktor",
            "serializationLibrary" to "kotlinx_serialization",
        ),
    )
}

tasks.compileKotlin {
    dependsOn(tasks.openApiGenerate)
}

tasks.matching { it.name == "runKtlintCheckOverMainSourceSet" }.configureEach {
    dependsOn(tasks.openApiGenerate)
}
