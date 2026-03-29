package io.github.aeshen.observability.codec.impl

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class EventSchemaDocsParityTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `event schema docs stay in sync with json schema`() {
        val markdown = Files.readString(Path.of("docs", "event-schema.md"), StandardCharsets.UTF_8)
        val readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8)
        val schemaNode =
            objectMapper.readTree(
                Files.readString(
                    Path.of("docs", "schema", "event-envelope-v1.schema.json"),
                    StandardCharsets.UTF_8,
                ),
            )

        val requiredFromDocs = parseTopLevelFields(markdown, "### Required fields", "### Optional fields")
        val optionalFromDocs = parseTopLevelFields(markdown, "### Optional fields", "## JSON Example")
        val schemaSection = readme.substringAfter("## Event Schema Contract").substringBefore("---")
        val requiredFromReadme = parseInlineFieldList(schemaSection, "- Required:")
        val optionalFromReadme = parseInlineFieldList(schemaSection, "- Optional:")

        val requiredFromSchema = schemaNode["required"].map { it.asText() }.toSet()
        val propertiesFromSchema = schemaNode["properties"].fieldNames().asSequence().toSet()
        val optionalFromSchema = propertiesFromSchema - requiredFromSchema

        assertEquals(
            requiredFromSchema,
            requiredFromDocs,
            "Required fields in docs/event-schema.md are out of sync with docs/schema/event-envelope-v1.schema.json",
        )
        assertEquals(
            optionalFromSchema,
            optionalFromDocs,
            "Optional fields in docs/event-schema.md are out of sync with docs/schema/event-envelope-v1.schema.json",
        )
        assertEquals(
            requiredFromSchema,
            requiredFromReadme,
            "Required fields in README.md are out of sync with docs/schema/event-envelope-v1.schema.json",
        )
        assertEquals(
            optionalFromSchema,
            optionalFromReadme,
            "Optional fields in README.md are out of sync with docs/schema/event-envelope-v1.schema.json",
        )
    }

    private fun parseTopLevelFields(
        markdown: String,
        sectionHeader: String,
        nextHeader: String,
    ): Set<String> {
        val section =
            markdown
                .substringAfter(sectionHeader)
                .substringBefore(nextHeader)

        return section
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.startsWith("- `") }
            .mapNotNull { line ->
                Regex("^- `([^`]+)`").find(line)?.groupValues?.get(1)
            }
            .toSet()
    }

    private fun parseInlineFieldList(
        section: String,
        prefix: String,
    ): Set<String> {
        val line =
            section.lineSequence().firstOrNull {
                it.trimStart().startsWith(prefix)
            }.orEmpty()
        return Regex("`([^`]+)`")
            .findAll(line)
            .map { it.groupValues[1] }
            .toSet()
    }
}
