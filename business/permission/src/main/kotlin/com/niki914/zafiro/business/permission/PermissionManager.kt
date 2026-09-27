package com.niki914.zafiro.business.permission

import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.service.requireService

/**
 * 权限门面（业务方唯一认识的东西）：静默查、默认链申请、自定义链申请。
 * 实现经 ServiceRegistry 装配，调用点按接口取，不认识实现类。
 */
interface PermissionManager {

    /** 静默查询：只看不动手，不弹窗不跳页，同步返回。 */
    fun status(permission: Permission): PermissionState

    /**
     * 默认链申请：`request(permission, channels)` 空参的等价写法。
     * 业务能确定用哪条链时调 [request] 的 channels 重载；要自定义通道才用 scope 写法。
     */
    suspend fun request(permission: Permission): PermissionResult

    /** 自定义链申请：按传入通道顺序跑；空 channels = 用该 permission 的默认链。 */
    suspend fun request(permission: Permission, vararg channels: Channel): PermissionResult

    /** 自定义链入口：挂起式，不占线程等回调。 */
    fun applyScope(vararg channels: Channel): PermissionScope
}

/** channels 为空 = 走该 permission 的默认链；两处自定义链入口共用这一个兜底。 */
private fun chainOf(permission: Permission, channels: List<Channel>): List<Channel> =
    channels.ifEmpty { PermissionManagerImpl.defaultChain(permission) }

/** 自定义链：定钥匙顺序再申请。 */
interface PermissionScope {
    suspend fun request(permission: Permission): PermissionResult
}

/**
 * 生产实现，零构造参数：协作者自己经注册表取，组合根只负责登记（见 AppServices）。
 *
 * 仅主进程可用：ApplicationService 只装在主进程组合根，宿主进程取不到即构造失败——
 * 宿主进程的 packageName 是宿主包（Breeno/XiaoAi），拿它拼 ComponentName 与 shell 命令会错包。
 */
class PermissionManagerImpl : PermissionManager {

    private val appService: ApplicationService = requireService()
    private val app: Context = appService.getApplication()
    private val accessibilityService = ComponentName(
        app.packageName,
        "${app.packageName}.mod.feat.ZafiroAccessibilityService",
    )

    private val engine = PermissionEngine(
        currentApi = Build.VERSION.SDK_INT,
        handlers = mapOf(
            Channel.ROOT_SHELL to RootShellHandler(app, accessibilityService),
            Channel.SHIZUKU to ShizukuHandler(app, accessibilityService),
            Channel.SYSTEM_DIALOG to SystemDialogHandler(appService),
            Channel.JUMP_SETTINGS to JumpSettingsHandler(app, appService, accessibilityService),
        ),
    )

    override fun status(permission: Permission): PermissionState =
        engine.status(permission)

    override suspend fun request(permission: Permission): PermissionResult =
        engine.request(permission, defaultChain(permission))

    override suspend fun request(permission: Permission, vararg channels: Channel): PermissionResult =
        engine.request(permission, chainOf(permission, channels.toList()))

    override fun applyScope(vararg channels: Channel): PermissionScope =
        Scope(engine, channels.toList())

    private class Scope(
        private val engine: PermissionEngine,
        private val channels: List<Channel>,
    ) : PermissionScope {
        override suspend fun request(permission: Permission): PermissionResult =
            engine.request(permission, chainOf(permission, channels))
    }

    companion object {
        /** PRD 默认链 */
        internal fun defaultChain(permission: Permission): List<Channel> = when (permission) {
            Permission.OVERLAY, Permission.ACCESSIBILITY ->
                listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.JUMP_SETTINGS)
            Permission.NOTIFICATION ->
                // 有 root / Shizuku 时优先静默授权（shell 执行 pm grant / appops），
                // 不可用或命令被系统拒纳时降级到系统弹窗与跳设置页
                listOf(Channel.ROOT_SHELL, Channel.SHIZUKU, Channel.SYSTEM_DIALOG, Channel.JUMP_SETTINGS)
            Permission.ROOT -> listOf(Channel.ROOT_SHELL)
            Permission.SHIZUKU -> listOf(Channel.SHIZUKU)
        }
    }
}
