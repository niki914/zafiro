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

/** 契约里声明过的事件。当前没有子类型。 */
sealed interface EventDef

/** 契约里声明过的扩展键。当前没有子类型。 */
sealed interface CustomKeyDef

/** 扩展口的值：字符串、整数、布尔。 */
sealed interface CustomValue {
    data class Str(val value: String) : CustomValue
    data class Num(val value: Int) : CustomValue
    data class Bool(val value: Boolean) : CustomValue
}
