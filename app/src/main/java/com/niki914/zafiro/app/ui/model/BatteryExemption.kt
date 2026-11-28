package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService

/**
 * 打开不休眠开关时申请一次系统电池优化豁免。已授权则直接返回。
 * 申请失败不阻止开关打开。
 */
internal suspend fun requestBatteryExemptionIfNeeded() {
    val manager = requireService<PermissionManager>()
    if (manager.status(Permission.BATTERY_OPTIMIZATION) == PermissionState.GRANTED) {
        return
    }
    manager.request(Permission.BATTERY_OPTIMIZATION)
}
