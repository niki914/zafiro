package com.niki914.nexus.agentic.takeover

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget
import com.niki914.nexus.agentic.util.TextPatternMatcher

data class TakeoverDecision(
    val target: RuntimeTakeoverTarget,
    val matchedRuleId: String? = null,
    val matchedRuleName: String? = null,
)

object TakeoverResolver {
    private const val LOG_TAG = "niki914_nexus_TakeoverResolver"

    fun resolve(
        query: String,
        rules: List<RuntimeTakeoverRule>,
        defaultTarget: RuntimeTakeoverTarget,
    ): TakeoverDecision {
        val matchedRule = rules.firstOrNull { rule ->
            rule.enabled && TextPatternMatcher.matchesAny(query, rule.patterns)
        }
        if (matchedRule == null) {
            Logger.d(
                LOG_TAG,
                "takeover default target=$defaultTarget rulesCount=${rules.size} " +
                    "queryLength=${query.length}"
            )
            return TakeoverDecision(defaultTarget)
        }

        Logger.i(
            LOG_TAG,
            "takeover matched ruleId=${matchedRule.id} ruleName=${matchedRule.name} " +
                "target=${matchedRule.target} rulesCount=${rules.size} queryLength=${query.length}"
        )
        return TakeoverDecision(
            target = matchedRule.target,
            matchedRuleId = matchedRule.id,
            matchedRuleName = matchedRule.name,
        )
    }
}
