package com.niki914.zafiro.repo

data class SkillParsedMetadata(
    val name: String,
    val description: String,
)

class SkillFrontmatterParser {
    fun parse(id: String, content: String): SkillParsedMetadata {
        val fallback = SkillParsedMetadata(name = id, description = "")
        val lines = content.lines()
        if (lines.firstOrNull() != FRONTMATTER_DELIMITER) {
            return fallback
        }

        val closingIndex = lines.drop(1).indexOfFirst { it == FRONTMATTER_DELIMITER }
        if (closingIndex < 0) {
            return fallback
        }

        val frontmatterLines = lines.subList(1, closingIndex + 1)
        val values = mutableMapOf<String, String>()
        var currentKey: String? = null

        frontmatterLines.forEach { line ->
            if (line.isBlank() || line.trimStart().startsWith("#")) {
                return@forEach
            }
            if (line.first().isWhitespace() && currentKey != null) {
                if (currentKey == "name" || currentKey == "description") {
                    val continuation = line.trim()
                    if (continuation.isNotEmpty()) {
                        val existing = values[currentKey].orEmpty()
                        values[currentKey] = when {
                            existing.isEmpty() || existing == ">" || existing == "|" -> continuation
                            else -> "$existing $continuation"
                        }
                    }
                }
                return@forEach
            }
            val separator = line.indexOf(':')
            if (separator < 0) {
                return fallback
            }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            currentKey = key
            if (key == "name" || key == "description") {
                values[key] = value
            }
        }

        return SkillParsedMetadata(
            name = values["name"]?.takeIf { it.isNotBlank() } ?: id,
            description = values["description"].orEmpty(),
        )
    }

    private companion object {
        const val FRONTMATTER_DELIMITER = "---"
    }
}
