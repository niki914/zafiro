package com.niki914.zafiro.repo

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillFrontmatterParserTest {

    private val parser = SkillFrontmatterParser()

    @Test
    fun parse_readsNameAndDescriptionFromLeadingFrontmatter() {
        val metadata = parser.parse(
            id = "skill-a",
            content = """
                ---
                name: Skill A
                description: Does one thing.
                ---
                # Body
            """.trimIndent(),
        )

        assertEquals("Skill A", metadata.name)
        assertEquals("Does one thing.", metadata.description)
    }

    @Test
    fun parse_fallsBackToIdWhenNameIsMissingOrBlank() {
        val missingName = parser.parse(
            id = "skill-a",
            content = """
                ---
                description: Has no name.
                ---
            """.trimIndent(),
        )
        val blankName = parser.parse(
            id = "skill-b",
            content = """
                ---
                name:
                description: Has blank name.
                ---
            """.trimIndent(),
        )

        assertEquals("skill-a", missingName.name)
        assertEquals("skill-b", blankName.name)
    }

    @Test
    fun parse_fallsBackToEmptyDescriptionWhenMissing() {
        val metadata = parser.parse(
            id = "skill-a",
            content = """
                ---
                name: Skill A
                ---
            """.trimIndent(),
        )

        assertEquals("Skill A", metadata.name)
        assertEquals("", metadata.description)
    }

    @Test
    fun parse_returnsFallbackWhenFrontmatterIsMissingOrMalformed() {
        val missing = parser.parse("skill-a", "# Skill A")
        val unclosed = parser.parse(
            id = "skill-b",
            content = """
                ---
                name: Skill B
            """.trimIndent(),
        )

        assertEquals(SkillParsedMetadata(name = "skill-a", description = ""), missing)
        assertEquals(SkillParsedMetadata(name = "skill-b", description = ""), unclosed)
    }

    @Test
    fun parse_allowsDuplicateDisplayNamesForDifferentIds() {
        val first = parser.parse("group-a/skill-a", frontmatter(name = "Shared Name"))
        val second = parser.parse("group-b/skill-a", frontmatter(name = "Shared Name"))

        assertEquals("Shared Name", first.name)
        assertEquals("Shared Name", second.name)
    }

    private fun frontmatter(name: String): String {
        return """
            ---
            name: $name
            description:
            ---
        """.trimIndent()
    }
}
