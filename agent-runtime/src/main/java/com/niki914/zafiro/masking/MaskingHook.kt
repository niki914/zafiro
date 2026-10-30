package com.niki914.zafiro.masking

import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.zafiro.settings.model.RuntimeMaskingRule

class MaskingHook(
    private val sessionMasker: SessionMasker = SessionMasker(),
    private val listMaskingRules: suspend () -> List<RuntimeMaskingRule>,
) : Hooks {

    override suspend fun beforeSerialization(request: SerializationHolder) {
        val rules = listMaskingRules().filter { it.enabled && it.patterns.isNotEmpty() }
        if (rules.isEmpty()) return

        val maskedHistory = sessionMasker.maskHistory(request.history, rules)
        if (maskedHistory != request.history) {
            request.write(request.snapshot, maskedHistory, "masking")
        }
    }
}
