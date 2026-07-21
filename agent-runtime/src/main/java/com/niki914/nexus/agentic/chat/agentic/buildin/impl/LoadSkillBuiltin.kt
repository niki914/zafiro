package com.niki914.nexus.agentic.chat.agentic.buildin.impl

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class LoadSkillBuiltin : BuiltinTool() {
    override val name: String = "load_skill"

    override val description: String =
        "Load a Nexus skill by id when its full SKILL.md content is needed."

    override val defaultEnabled: Boolean = true

    override fun configure(config: LocalToolConfig) {
        config.description = description
        config.string("id") {
            description = "Skill id from available_skills."
            required = true
        }
        config.rawJsonSchema(LOAD_SKILL_SCHEMA)
    }

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val skillId = when (val result = parseSkillId(request.argumentsJson)) {
            is SkillIdParseResult.Success -> result.id
            is SkillIdParseResult.InvalidJson -> {
                return BuiltinToolResult.failure(
                    code = "INVALID_ARGUMENTS_JSON",
                    message = "load_skill arguments must be a JSON object with an id field.",
                    hint = """Example: {"id":"skill-a"}""",
                    fieldErrors = mapOf("argumentsJson" to result.message),
                )
            }

            SkillIdParseResult.MissingId -> {
                return BuiltinToolResult.failure(
                    code = "MISSING_SKILL_ID",
                    message = "load_skill requires a non-blank skill id.",
                    hint = "Use an id from the available_skills prompt block.",
                    fieldErrors = mapOf("id" to "Skill id is required."),
                )
            }
        }

        return try {
            val skill = RuntimeEnvironment.awaitSettingsGateway().loadSkill(skillId)
                ?: return BuiltinToolResult.failure(
                    code = "SKILL_NOT_FOUND",
                    message = "Skill '$skillId' was not found.",
                    hint = "Use an id from the available_skills prompt block.",
                    fieldErrors = mapOf("id" to "Unknown skill id."),
                )
            if (!skill.enabled) {
                return BuiltinToolResult.failure(
                    code = "SKILL_DISABLED",
                    message = "Skill '$skillId' is disabled.",
                    hint = "Use an enabled id from the available_skills prompt block.",
                    fieldErrors = mapOf("id" to "Disabled skill id."),
                )
            }
            BuiltinToolResult.success(
                message = "Skill loaded.",
                data = skill.toJsonData(),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            BuiltinToolResult.failure(
                code = "SETTINGS_READ_FAILED",
                message = "Failed to load skill: ${throwable.message ?: throwable::class.java.simpleName}.",
                hint = "Retry after confirming the settings provider is available.",
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

    private fun RuntimeLoadedSkill.toJsonData(): JsonObject {
        return JsonObject(
            linkedMapOf(
                "skill_name" to JsonPrimitive(name),
                "skill_path" to JsonPrimitive(relativePath),
                "skill_content" to JsonPrimitive(content),
            )
        )
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
