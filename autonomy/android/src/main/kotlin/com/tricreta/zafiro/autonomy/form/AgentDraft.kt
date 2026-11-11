package com.tricreta.zafiro.autonomy.form

import com.tricreta.zafiro.autonomy.core.AgentNames
import com.tricreta.zafiro.autonomy.data.AgentProfileEntity

enum class AgentField { NAME, INSTRUCTIONS }

sealed interface AgentDraftResult {
    data class Ok(val agent: AgentProfileEntity) : AgentDraftResult
    data class Invalid(val field: AgentField, val message: String) : AgentDraftResult
}

/** Editable agent profile, independent of any UI toolkit so validation is unit-tested. */
data class AgentDraft(
    val name: String = "",
    val instructions: String = "",
    val configId: String = "",
    /** Comma/space/newline separated. */
    val tools: String = "",
    val skills: String = "",
    val usesScreen: Boolean = false,
    val enabled: Boolean = true,
) {
    fun toEntity(id: String, createdAtMs: Long?, nowMs: Long, existingNames: Collection<String>, ownName: String?): AgentDraftResult {
        AgentNames.validate(name, existingNames, ownName)?.let { return AgentDraftResult.Invalid(AgentField.NAME, it) }
        if (instructions.isBlank()) return AgentDraftResult.Invalid(AgentField.INSTRUCTIONS, "Tell the agent what it is and how it should work.")
        return AgentDraftResult.Ok(
            AgentProfileEntity(
                id = id, name = name.trim(), instructions = instructions.trim(), configId = configId,
                tools = splitList(tools).joinToString(","), skills = splitList(skills).joinToString(","),
                usesScreen = usesScreen, enabled = enabled, createdAtMs = createdAtMs ?: nowMs, updatedAtMs = nowMs,
            ),
        )
    }

    companion object {
        fun splitList(text: String): List<String> = text.split(',', ';', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun from(a: AgentProfileEntity) = AgentDraft(
            name = a.name, instructions = a.instructions, configId = a.configId,
            tools = a.tools.replace(",", ", "), skills = a.skills.replace(",", ", "), usesScreen = a.usesScreen, enabled = a.enabled,
        )
    }
}
