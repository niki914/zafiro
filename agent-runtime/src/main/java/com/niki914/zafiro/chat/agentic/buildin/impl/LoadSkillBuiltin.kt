package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.TextToolResult
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.util.ToolOutputTruncator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LoadSkillBuiltin : TextResultBuiltinTool() {
    override val name: String = "load_skill"

    override val description: String =
        "Load a Zafiro skill by id. Returns the skill's SKILL.md content; if it exceeds " +
                "the limit, the result ends with the absolute path to the file — use terminal " +
                "to read the full content from there."

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? get() = LOAD_SKILL_SCHEMA

    override suspend fun invokeText(request: BuiltinToolRequest): TextToolResult {
        val skillId = when (val result = parseSkillId(request.argumentsJson)) {
            is SkillIdParseResult.Success -> result.id
            is SkillIdParseResult.InvalidJson -> {
                return TextToolResult.failure(
                    code = "INVALID_ARGUMENTS_JSON",
                    message = "load_skill arguments must be a JSON object with an id field. " +
                            "Example: {\"id\":\"skill-a\"} (${result.message})",
                )
            }

            SkillIdParseResult.MissingId -> {
                return TextToolResult.failure(
                    code = "MISSING_SKILL_ID",
                    message = "load_skill requires a non-blank skill id. " +
                            "Use an id from the available_skills prompt block.",
                )
            }
        }

        return try {
            val skill = RuntimeEnvironment.awaitSettingsGateway().loadSkill(skillId)
                ?: return TextToolResult.failure(
                    code = "SKILL_NOT_FOUND",
                    message = "Skill '$skillId' was not found. " +
                            "Use an id from the available_skills prompt block.",
                )
            if (!skill.enabled) {
                return TextToolResult.failure(
                    code = "SKILL_DISABLED",
                    message = "Skill '$skillId' is disabled. " +
                            "Use an enabled id from the available_skills prompt block.",
                )
            }
            TextToolResult.success(
                truncateSkillContent(skill.content, skill.absolutePath)
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            TextToolResult.failure(
                code = "SETTINGS_READ_FAILED",
                message = "Failed to load skill: ${throwable.message ?: throwable::class.java.simpleName}. " +
                        "Retry after confirming the settings provider is available.",
            )
        }
    }

    private fun parseSkillId(argumentsJson: String): SkillIdParseResult {
        val element = try {
            Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
        } catch (throwable: SerializationException) {
            return SkillIdParseResult.InvalidJson("argumentsJson is not valid JSON.")
        } catch (throwable: IllegalArgumentException) {
            return SkillIdParseResult.InvalidJson("argumentsJson is not valid JSON.")
        }
        val obj = element as? JsonObject
            ?: return SkillIdParseResult.InvalidJson("argumentsJson must be a JSON object.")
        val id = obj.stringOrNull("id")?.trim()?.ifBlank { null }
        return id?.let(SkillIdParseResult::Success) ?: SkillIdParseResult.MissingId
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    /**
     * 双限制（2000 行 / 50KB）head 截断（对齐 ToolOutputTruncator），截断时
     * 在内容尾部附绝对路径提示，模型可用 terminal 自取全量。
     */
    private fun truncateSkillContent(content: String, absolutePath: String): String {
        val truncation = ToolOutputTruncator.truncateHead(content)
        if (!truncation.truncated) return content
        return buildString {
            append(truncation.content)
            append("\n\n[Content truncated: full SKILL.md is at ")
            append(absolutePath)
            append(" — use terminal to read it.]")
        }
    }

    private sealed interface SkillIdParseResult {
        data class Success(val id: String) : SkillIdParseResult
        data class InvalidJson(val message: String) : SkillIdParseResult
        data object MissingId : SkillIdParseResult
    }

    companion object {
        private const val LOAD_SKILL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "id": {
                  "type": "string",
                  "description": "Skill id from the available_skills prompt block."
                }
              },
              "required": ["id"]
            }
        """
    }
}
