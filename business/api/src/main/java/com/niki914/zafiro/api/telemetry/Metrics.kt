package com.niki914.zafiro.api.telemetry

import com.niki914.zafiro.service.findService

/**
 * 业务侧唯一入口。
 * 取实现走 [findService]；未登记时为 null（静默无操作）。
 */
object Metrics {
    private fun telemetry(): Telemetry? = findService<Telemetry>()

    fun onboardComplete(provider: String) {
        telemetry()?.record(EventDef.OnboardComplete, label = provider)
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
        telemetry()?.record(EventDef.TakeoverInvoke, label = host)
        telemetry()?.record(EventDef.FirstTakeover)
    }
}
