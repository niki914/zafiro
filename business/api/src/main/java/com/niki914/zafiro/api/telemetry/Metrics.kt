package com.niki914.zafiro.api.telemetry

import com.niki914.zafiro.service.findService

/**
 * 业务侧唯一入口。当前没有业务函数。
 * 取实现走 [findService]；未登记时为 null。
 */
object Metrics {
    /** 业务函数从这里取实现。未登记时是 null。 */
    @Suppress("unused")
    private fun telemetry(): Telemetry? = findService<Telemetry>()
}
