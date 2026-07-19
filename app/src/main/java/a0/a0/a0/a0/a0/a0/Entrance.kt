package a0.a0.a0.a0.a0.a0

import android.content.pm.PackageManager
import android.os.Build
import com.niki914.nexus.agentic.mod.HookLocalSettings
import com.niki914.nexus.agentic.mod.XService
import com.niki914.nexus.agentic.mod.feat.hyper.XiaoaiChatHook
import com.niki914.nexus.agentic.mod.feat.oppo.BreenoChatHook
import com.niki914.nexus.agentic.repo.WebSettingsFailureReason
import com.niki914.nexus.agentic.repo.WebSettingsResult
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.agentic.runtime.createAppRuntimeBridge
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
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

        private fun hostDisplayName(packageName: String?): String {
            return when (HostApp.fromPackageName(packageName)) {
                HostApp.Breeno -> "小布助手"
                HostApp.XiaoAi -> "小爱同学"
                else -> "助手"
            }
        }

        private fun hostVersionName(ctx: android.content.Context, packageName: String?): String? {
            if (packageName == null) return null
            return try {
                val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ctx.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    ctx.packageManager.getPackageInfo(packageName, 0)
                }
                pi.versionName
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
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
            RuntimeEnvironment.install(createAppRuntimeBridge())

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
                    XService.postNotification(
                        title = "网络异常",
                        content = "网络连接失败，请检查网络后重试",
                        uri = null,
                    )
                }

                isNoSupportedVersion -> {
                    val hostName = hostDisplayName(targetPkg)
                    val hostVersion = hostVersionName(ctx, targetPkg)
                    XService.postNotification(
                        title = "宿主版本未适配",
                        content = "当前${hostName}版本还未被 Nexus 支持" +
                            (hostVersion?.let { "\n宿主版本：$it" } ?: ""),
                        uri = "https://github.com/niki914/agentic-nexus/issues/new",
                    )
                }
            }

            val configObj = webSettingsResult.configOrNull()
            if (configObj != null) {
                onSettingsFetched(params, targetPkg)
            }
        }
    }

    private fun onSettingsFetched(params: XC_LoadPackage.LoadPackageParam, targetPkg: String?) {
        // 根据 targetPkg 进行映射和 Hook 路由
        val hostApp = HostApp.fromPackageName(params.packageName)
        val hookInstance: Hook? = when {
            targetPkg != params.packageName -> null
            hostApp == HostApp.Breeno -> BreenoChatHook(scope)
            hostApp == HostApp.XiaoAi -> XiaoaiChatHook(scope)
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
