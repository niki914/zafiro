package com.niki914.zafiro.business.remote

import com.niki914.zafiro.api.telemetry.CustomKeyDef
import com.niki914.zafiro.api.telemetry.CustomValue
import com.niki914.zafiro.api.telemetry.EventDef
import com.niki914.zafiro.api.telemetry.Telemetry
import com.niki914.zafiro.business.remote.activity.ActivityTracker
import com.niki914.zafiro.business.remote.ledger.FileLedgerStore
import com.niki914.zafiro.business.remote.ledger.Ledger
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.time.Clock

/**
 * [Telemetry] 的实现。
 *
 * [attach] 把当天记为活跃，并在主进程回到前台时继续记。
 * 契约里的事件和扩展键都是空的，三个记账方法没有可写入的项。
 * 上报用的 HTTP 客户端留到发送那一期再接。
 */
class TelemetryImpl(
    directory: File,
    versionCode: Int,
    scope: CoroutineScope,
    clock: Clock = Clock.systemUTC(),
) : Telemetry {
    private val tracker = ActivityTracker(
        ledger = Ledger(clock, FileLedgerStore(directory)),
        versionCode = versionCode,
        scope = scope,
    )

    fun attach() {
        tracker.attach()
    }

    override fun record(
        event: EventDef,
        label: String?,
        n: Int,
        custom: Map<String, CustomValue>,
    ) = Unit

    override fun setEnvelopeCustom(key: CustomKeyDef, value: CustomValue?) = Unit

    override fun setDayCustom(key: CustomKeyDef, value: CustomValue?) = Unit
}
