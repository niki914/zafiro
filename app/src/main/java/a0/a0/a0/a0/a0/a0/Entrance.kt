package a0.a0.a0.a0.a0.a0

import com.niki914.nexus.agentic.app.getInstalledPackageVersion
import com.niki914.nexus.agentic.mod.HookLocalSettings
import com.niki914.nexus.agentic.mod.XService
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiChatHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoChatHook
import com.niki914.nexus.agentic.repo.WebSettingsFailureReason
import com.niki914.nexus.agentic.repo.WebSettingsResult
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.client.AgentRuntimeClient
import com.niki914.nexus.h.IXposed
import com.niki914.nexus.h.core.runtime.Hook
import com.niki914.nexus.h.core.runtime.Runtime
import com.niki914.nexus.h.core.runtime.RuntimeBootstrap
import com.niki914.nexus.h.util.ContextHook
import com.niki914.nexus.h.util.ContextProvider
import com.niki914.nexus.h.util.HookSideLoader
import com.niki914.nexus.ipc.HostApp
import com.niki914.nexus.ipc.XValues
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// 仅在锁屏时生效
class Entrance : IXposed() {
    companion object {
        private val scope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    }

    override fun getTarget() =
        Target.filter(*XValues.appList.toTypedArray())

    override fun onLoad(params: XC_LoadPackage.LoadPackageParam) {
        HookSideLoader.load(scope, ContextHook(), params)
//        HookSideLoader.load(scope, ActivityHook(), params)
//        HookSideLoader.load(scope, FloatWindowHook(), params)
        scope.launch(Dispatchers.IO) {
            val ctx = ContextProvider.await()
            XRepo.init(ctx)
            val client = AgentRuntimeClient(ctx)
            client.connect()

            HookLocalSettings.update(ctx)
            val webSettingsResult = XRepo.web.await()
            val targetPkg = params.packageName
            val isFallbackVersion =
                webSettingsResult is WebSettingsResult.Success && webSettingsResult.isFallbackVersion
            val isNetworkError =
                webSettingsResult is WebSettingsResult.RequestFailed &&
                    webSettingsResult.reason == WebSettingsFailureReason.NetworkUnavailable
            val isNoSupportedVersion =
                isFallbackVersion || (
                    webSettingsResult is WebSettingsResult.RequestFailed &&
                        webSettingsResult.reason == WebSettingsFailureReason.ServerError
                    )

            when {
                isNetworkError -> {
                    XService.postNetworkErrorNotification(client)
                }

                isNoSupportedVersion -> {
                    XService.postUnsupportedVersionNotification(
                        hostApp = HostApp.fromPackageName(targetPkg),
                        hostVersion = targetPkg?.let {
                            ctx.getInstalledPackageVersion(it)?.versionName
                        },
                        client = client,
                    )
                }
            }

            val configObj = webSettingsResult.configOrNull()
            if (configObj != null) {
                onSettingsFetched(params, targetPkg, client)
            }
        }
    }

    private fun onSettingsFetched(params: XC_LoadPackage.LoadPackageParam, targetPkg: String?, client: AgentRuntimeClient) {
        // 根据 targetPkg 进行映射和 Hook 路由
        val hostApp = HostApp.fromPackageName(params.packageName)
        val hookInstance: Hook? = when {
            targetPkg != params.packageName -> null
            hostApp == HostApp.Breeno -> BreenoChatHook(scope, client)
            hostApp == HostApp.XiaoAi -> XiaoaiChatHook(scope, client)
            else -> null
        }

        hookInstance ?: return

        RuntimeBootstrap.installIfNeeded(
            params,
            create = {
                Runtime(
                    scope = scope,
                    hooks = listOf(
                        hookInstance
                    )
                )
            }
        )
    }
}
