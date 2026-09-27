package com.niki914.zafiro.repo

import com.niki914.xsettings.XSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * [XSettings] 的 app 侧实现：全部委托给 [XRepo]。
 *
 * 零构造参数（见 AppServices），本类只做「接口 → XRepo」的类型转换，
 * 不持有状态、不做缓存。
 */
object XSettingsImpl : XSettings {

    override val floatingBallAutoExpand: StateFlow<Boolean>
        get() = XRepo.floatingBallAutoExpandSetting
}
