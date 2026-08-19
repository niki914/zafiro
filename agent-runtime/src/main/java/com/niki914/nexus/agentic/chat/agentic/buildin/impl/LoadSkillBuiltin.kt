package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.TextResultBuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LoadSkillBuiltin : TextResultBuiltinTool() {
    override val name: String = "load_skill"

    override val description: String =
        "Load a Nexus skill by id when its full SKILL.md content is needed."

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
            TextToolResult.success(skill.content)
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
            ?: obj.stringOrNull("skill_id")?.trim()?.ifBlank { null }
        return id?.let(SkillIdParseResult::Success) ?: SkillIdParseResult.MissingId
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
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
                },
                "skill_id": {
                  "type": "string",
                  "description": "Alias for id."
                }
              },
              "required": ["id"]
            }
        """
    }
}
