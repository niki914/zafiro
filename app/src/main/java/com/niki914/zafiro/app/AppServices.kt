package com.niki914.zafiro.app

import android.app.Application
import android.content.ComponentName
import com.niki914.xsettings.XSettings
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.telemetry.Telemetry
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.conversation.RoomConversationStore_Tmp
import com.niki914.zafiro.business.agent.AgentManagerImpl
import com.niki914.zafiro.business.agent.ConversationStore_Tmp
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.application.ApplicationServiceImpl
import com.niki914.zafiro.business.files.FilesService
import com.niki914.zafiro.business.files.FilesServiceImpl
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.business.notification.NotificationChannelManagerImpl
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionManagerImpl
import com.niki914.zafiro.business.remote.TelemetryImpl
import com.niki914.zafiro.mcp.service.McpServiceImpl
import com.niki914.zafiro.mcp.service.McpToolAdapter
import com.niki914.zafiro.mod.feat.ZafiroAccessibilityService
import com.niki914.zafiro.repo.SharedHttp
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.repo.XSettingsImpl
import com.niki914.zafiro.service.installService

/**
 * 主进程的组合根：`installService` 只在这里出现。
 *
 * 装配按进程划分（宿主进程将来另有自己的组合根）。放在独立对象里而不是散在
 * `App.onCreate`，是因为这里会成为「谁依赖谁」的唯一清单——新增能力、替换实现、
 * 做宿主代理实现都只改这一处。
 *
 * 这里只做登记：被装的实现自己持 scope、经注册表取协作者，所以没有构造参数、
 * 没有初始化顺序之外的知识。调用点按接口类型取（见 `com.niki914.zafiro.service`），
 * 不认识实现类——**这个文件里不出现服务查找调用**（单测守卫）。
 *
 * 进程级初始化（`XRepo.init` / `ContextProvider` / `RuntimeEnvironment` 等）
 * 不是依赖装配，留在 [App.onCreate]。
 */
object AppServices {

    fun install(application: Application) {
        // 会话持久化端口：Room 在 app 侧，实现侧经它读写会话记录
        installService<ConversationStore_Tmp>(RoomConversationStore_Tmp())
        val agentManager = AgentManagerImpl()
        installService<AgentManager>(agentManager)
        installService<AgentControl>(agentManager.control)

        // 前台能力：必须在 PermissionManager 之前装（它经注册表取 ApplicationService）
        val appService = ApplicationServiceImpl(application)
        appService.attach()
        installService<ApplicationService>(appService)

        // 无障碍组件名只能由 app 侧给出：类名不随 applicationId（`.debug` 后缀）改变
        installService<PermissionManager>(
            PermissionManagerImpl(
                ComponentName(application, ZafiroAccessibilityService::class.java),
            )
        )
        // 附件落地（SAF uri → 真实路径 + 全局文件访问权）：自持 PermissionManager，零构造参数
        installService<FilesService>(FilesServiceImpl())
        installService<NotificationChannelManager>(NotificationChannelManagerImpl(application))

        // 本地配置读取口：给 app 以外的模块按需取用
        installService<XSettings>(XSettingsImpl)

        // MCP 服务：把 Zafiro 的工具暴露成标准 MCP Streamable HTTP 端点
        // 2026-11-10: McpToolAdapter 只遍历 builtinRegistry（Kotlin 内置工具），自定义 py_*
        // 工具从来不可见于 MCP，只能经 py_meta_tools 间接调用。注入运行期读取口后，它们
        // 作为标准 MCP 工具直接暴露；每次重建都重新读取，新增/删除无需重启 app。
        installService<McpService>(
            McpServiceImpl(
                toolAdapter = McpToolAdapter(
                    customToolsProvider = { XRepo.customPyTools.list() },
                ),
            ) { XRepo.mcpService.get() }
        )

        // 遥测服务：后台账本同步与活跃探测
        val telemetry = TelemetryImpl(
            context = application,
            http = { SharedHttp.client },
            baseUrl = BuildConfig.TELEMETRY_BASE_URL,
            onboardingCompletedProvider = {
                runCatchingCancellable { XRepo.onboardingCompleted() }.getOrDefault(false)
            },
            telemetryEnabledProvider = {
                runCatchingCancellable { XRepo.telemetryEnabled() }.getOrDefault(true)
            },
        )
        telemetry.attach()
        installService<Telemetry>(telemetry)
    }
}
