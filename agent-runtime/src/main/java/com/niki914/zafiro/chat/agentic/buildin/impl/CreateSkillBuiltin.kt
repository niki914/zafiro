package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Validation and file layout for agent-made skills. Pure, so every rule is unit-tested. */
object SkillFiles {
    private val ID = Regex("[a-z0-9][a-z0-9-]{1,59}")
    const val MAX_BODY = 40_000
    const val MARKER = ".created_by_agent"

    fun validate(id: String, description: String, body: String): String? = when {
        !ID.matches(id) -> "id must be 2-60 characters: lowercase letters, digits and dashes, starting with a letter or digit."
        description.isBlank() || description.length > 300 -> "description is required (max 300 characters) and must say WHEN to use the skill."
        body.isBlank() -> "body is required."
        body.length > MAX_BODY -> "body is too long (max $MAX_BODY characters): split it into several skills."
        else -> null
    }

    fun render(id: String, description: String, body: String, adaptedFrom: String): String = buildString {
        appendLine("---")
        appendLine("name: $id")
        appendLine("description: ${description.replace('\n', ' ').trim()}")
        appendLine("license: see source")
        if (adaptedFrom.isNotBlank()) appendLine("adapted_from: ${adaptedFrom.replace('\n', ' ').trim()}")
        appendLine("---")
        appendLine()
        append(body.trim())
        appendLine()
    }
}

/** Lets the Skill Builder agent turn a repo, document, page or notes into an installed skill. */
class CreateSkillBuiltin(private val skillsDir: suspend () -> File = { File(ContextProvider.await().applicationContext.filesDir, "skills") }) : BuiltinTool() {
    override val name: String = "create_skill"
    override val defaultEnabled: Boolean = true
    override val description: String =
        "Install a new skill from text you have distilled (see skill skill-from-source). Arguments: id (lowercase-with-dashes), description (when to use it), body (Markdown instructions), " +
            "adapted_from (source URL or title, optional), overwrite (only for skills you created before). The skill appears on the Skills page and can be loaded with load_skill."
    override val inputSchemaJson: String =
        """{"type":"object","properties":{"id":{"type":"string"},"description":{"type":"string"},"body":{"type":"string"},"adapted_from":{"type":"string"},"overwrite":{"type":"boolean"}},"required":["id","description","body"]}"""

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val args = parseScreenObject(request.argumentsJson)
            ?: return BuiltinToolResult.failure(code = "INVALID_ARGUMENTS", message = "Arguments must be a JSON object.")
        val id = args.text("id").orEmpty()
        val desc = args.text("description").orEmpty()
        val body = (args["body"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        SkillFiles.validate(id, desc, body)?.let { return BuiltinToolResult.failure(code = "INVALID_SKILL", message = it) }
        val dir = File(skillsDir().also { it.mkdirs() }, id)
        val overwrite = args["overwrite"]?.toString() == "true"
        if (dir.exists() && !(overwrite && File(dir, SkillFiles.MARKER).isFile)) {
            return BuiltinToolResult.failure(
                code = "SKILL_EXISTS", message = "A skill named $id already exists and was not created by an agent.",
                hint = "Pick another id, or pass overwrite=true only for a skill you created earlier.",
            )
        }
        dir.mkdirs()
        File(dir, "SKILL.md").writeText(SkillFiles.render(id, desc, body, args.text("adapted_from").orEmpty()))
        File(dir, SkillFiles.MARKER).writeText("1")
        return BuiltinToolResult.success(
            message = "Skill $id installed. It is on the Skills page; switch it on there if it is not already.",
            data = buildJsonObject { put("id", id); put("path", "skills/$id/SKILL.md") },
        )
    }
}
