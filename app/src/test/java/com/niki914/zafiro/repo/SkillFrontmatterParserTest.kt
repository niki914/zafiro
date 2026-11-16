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

    @Test
    fun parse_readsMultilineIndentedDescription() {
        val metadata = parser.parse(
            id = "phone-use",
            content = """
                ---
                name: phone-use
                description: MUST Load this skill for ANY task that involves Android screen interaction or device control —
                  operating the virtual background display ('vd-*') or physical screen ('phone'), opening apps, tapping,
                  typing, scrolling, swiping, Back/Home navigation.
                ---
                # Body
            """.trimIndent(),
        )

        assertEquals("phone-use", metadata.name)
        assertEquals(
            "MUST Load this skill for ANY task that involves Android screen interaction or device control — " +
                "operating the virtual background display ('vd-*') or physical screen ('phone'), opening apps, tapping, " +
                "typing, scrolling, swiping, Back/Home navigation.",
            metadata.description,
        )
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
