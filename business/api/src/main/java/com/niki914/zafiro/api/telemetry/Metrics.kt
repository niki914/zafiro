package com.niki914.zafiro.api.telemetry

import com.niki914.zafiro.service.findService

/**
 * 业务侧唯一入口。
 * 取实现走 [findService]；未登记时为 null（静默无操作）。
 */
object Metrics {
    private fun telemetry(): Telemetry? = findService<Telemetry>()

    fun onboardComplete(provider: String) {
        telemetry()?.record(EventDef.OnboardComplete, label = sanitizeLabel(provider))
        telemetry()?.record(EventDef.FirstOnboard)
    }

    fun chatTurnStart(hasAttachment: Boolean) {
        val label = if (hasAttachment) "attachment" else "text"
        telemetry()?.record(EventDef.ChatTurnStart, label = label)
    }

    fun chatTurnEnd(status: String) {
        telemetry()?.record(EventDef.ChatTurnEnd, label = status)
        if (status == "success") {
            telemetry()?.record(EventDef.FirstChat)
        }
    }

    fun takeoverInvoke(host: String) {
        telemetry()?.record(EventDef.TakeoverInvoke, label = sanitizeLabel(host))
        telemetry()?.record(EventDef.FirstTakeover)
    }
}

/**
 * 标签清洗：将任意输入转换为合法的蛇形标签（^[a-z][a-z0-9_]*$，<= 40 字符）。
 * 连字符 `-` 和空白字符统一转换为 `_`，去除其他无效字符，截断至上限。
 */
fun sanitizeLabel(raw: String): String {
    if (raw.isBlank()) return "unknown"
    val replaced = buildString {
        for (ch in raw.trim().lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> append(ch)
                ch == '-' || ch.isWhitespace() || ch == '_' -> append('_')
                else -> { /* 去除其他无效字符 */ }
            }
        }
    }
    val collapsed = replaced.replace(Regex("_+"), "_").trim('_')
    if (collapsed.isEmpty()) return "unknown"
    val validStart = if (collapsed[0] in 'a'..'z') collapsed else "p_$collapsed"
    val truncated = validStart.take(TelemetryContract.LIMIT_NAME_LENGTH).trimEnd('_')
    return truncated.ifEmpty { "unknown" }
}
