package com.niki914.xsettings

import kotlinx.coroutines.flow.StateFlow

/**
 * App 模块以外的本地配置读取口。
 *
 * 存在的理由：配置的写入口在 app 的 XRepo，而消费方可能在别的模块
 * （例如 remote-view 的悬浮球状态机需要知道某个开关），app 模块无法被它们依赖。
 *
 * **懒规则：只有某个字段被 app 以外的模块真正依赖了，才往这里加。**
 * 不预先铺开字段，也不做「本地配置大杂烩」——加一行就说明有一处真实消费。
 *
 * 实现由 app 组合根注册（见 `AppServices`），调用方 `requireService()`。
 */
interface XSettings {

    /**
     * 悬浮球是否自动展开（回合结束、审批到达时）。
     * 消费方：remote-view 的 `FloatingBallViewModel`。
     */
    val floatingBallAutoExpand: StateFlow<Boolean>
}
