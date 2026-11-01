package com.niki914.zafiro.api.telemetry

import com.niki914.zafiro.service.installService

/**
 * 记账入口。主进程用 [installService] 按本接口登记实现。
 *
 * 事件和扩展键都是本包中的子类型。当前白名单为空，没有可传入的事件或键。
 */
interface Telemetry {
    fun record(
        event: EventDef,
        label: String? = null,
        n: Int = 1,
        custom: Map<String, CustomValue> = emptyMap(),
    )

    fun setEnvelopeCustom(key: CustomKeyDef, value: CustomValue?)

    fun setDayCustom(key: CustomKeyDef, value: CustomValue?)
}

/** 契约里声明过的事件。 */
sealed interface EventDef {
    val wireName: String

    data object AppColdStart : EventDef { override val wireName = "app_cold_start" }
    data object AppForeground : EventDef { override val wireName = "app_foreground" }
    data object AppActiveDuration : EventDef { override val wireName = "app_active_duration" }
    data object OnboardComplete : EventDef { override val wireName = "onboard_complete" }
    data object ChatTurnStart : EventDef { override val wireName = "chat_turn_start" }
    data object ChatTurnEnd : EventDef { override val wireName = "chat_turn_end" }
    data object TakeoverInvoke : EventDef { override val wireName = "takeover_invoke" }
    data object FirstOnboard : EventDef { override val wireName = "first_onboard" }
    data object FirstChat : EventDef { override val wireName = "first_chat" }
    data object FirstTakeover : EventDef { override val wireName = "first_takeover" }
}

/** 契约里声明过的扩展键。 */
sealed interface CustomKeyDef {
    val wireName: String

    data object UserCohort : CustomKeyDef { override val wireName = "user_cohort" }
}

/** 扩展口的值：字符串、整数、布尔。 */
sealed interface CustomValue {
    data class Str(val value: String) : CustomValue
    data class Num(val value: Int) : CustomValue
    data class Bool(val value: Boolean) : CustomValue
}
