package com.niki914.zafiro.app

import android.app.Application
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.app.conversation.RoomConversationStore_Tmp
import com.niki914.zafiro.business.agent.AgentImpl
import com.niki914.zafiro.business.agent.ConversationStore_Tmp
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.business.application.ApplicationServiceImpl
import com.niki914.zafiro.business.files.FilesService
import com.niki914.zafiro.business.files.FilesServiceImpl
import com.niki914.zafiro.business.notification.NotificationChannelManager
import com.niki914.zafiro.business.notification.NotificationChannelManagerImpl
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionManagerImpl
import com.niki914.zafiro.repo.XSettingsImpl
import com.niki914.zafiro.service.installService
import com.niki914.xsettings.XSettings

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
        // 会话门面：宽接口与窄接口指向同一实例
        installService<Agent>(AgentImpl)
        installService<AgentControl>(AgentImpl)

        // 前台能力：必须在 PermissionManager 之前装（它经注册表取 ApplicationService）
        val appService = ApplicationServiceImpl(application)
        appService.attach()
        installService<ApplicationService>(appService)

        installService<PermissionManager>(PermissionManagerImpl())
        // 附件落地（SAF uri → 真实路径 + 全局文件访问权）：自持 PermissionManager，零构造参数
        installService<FilesService>(FilesServiceImpl())
        installService<NotificationChannelManager>(NotificationChannelManagerImpl(application))

        // 本地配置读取口：给 app 以外的模块按需取用
        installService<XSettings>(XSettingsImpl)
    }
}
