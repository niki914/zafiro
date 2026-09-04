package a0.a0.a0.a0.a0.a0

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.store.HostApp
import com.niki914.store.XValues
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.xposed.runtime.IXposed
import com.niki914.xposed.runtime.runtime.Hook
import com.niki914.xposed.runtime.runtime.Runtime
import com.niki914.xposed.runtime.runtime.RuntimeBootstrap
import com.niki914.xposed.runtime.util.ContextHook
import com.niki914.xposed.runtime.util.HookSideLoader
import com.niki914.zafiro.app.BuildConfig
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.getInstalledPackageVersion
import com.niki914.zafiro.mod.HookLocalSettings
import com.niki914.zafiro.mod.feat.BaseConfigProvider
import com.niki914.zafiro.mod.feat.hyper.XiaoaiChatHook
import com.niki914.zafiro.mod.feat.oppo.BreenoChatHook
import com.niki914.zafiro.repo.XIpcDomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.client.AgentRuntimeClient
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// 仅在锁屏时生效
class Entrance : IXposed() {
    companion object {
        private const val LOG_TAG = "niki914_zafiro_Entrance"
        private val scope by lazy { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
    }

    override fun getTarget() =
        Target.filter(*XValues.appList.toTypedArray())

    override fun onLoad(params: XC_LoadPackage.LoadPackageParam) {
        // 宿主进程（Xposed 注入）不会执行 App.onCreate，必须在此注册 debug 门控
        Logger.setDebugProvider { BuildConfig.DEBUG }
        Logger.i(
            LOG_TAG,
            "onLoad process=${params.processName} package=${params.packageName}"
        )
        HookSideLoader.load(scope, ContextHook(), params)
//        HookSideLoader.load(scope, ActivityHook(), params)
//        HookSideLoader.load(scope, FloatWindowHook(), params)
        scope.launch(Dispatchers.IO) {
            val ctx = ContextProvider.await()
            val client = AgentRuntimeClient(ctx)
            client.connectAndAwait()
            XRepo.init(ctx, XIpcDomainSettingsStore(client))

            HookLocalSettings.update(ctx, client)

            // 配置从 res/raw/legacy_xposed_hooks/ 加载，注入 BaseConfigProvider。
            // TODO 未来恢复远程配置源时，改回 XRepo.web.await()。
            val config = loadConfigFromRaw(ctx, params.packageName)
            if (config != null) {
                // 注入嵌套的 config 对象（含 actions），而非整个顶层 JSON。
                val nestedConfig = config["config"] as? JsonObject
                if (nestedConfig != null) {
                    BaseConfigProvider.config = nestedConfig
                    onSettingsFetched(params, client)
                } else {
                    Logger.w(LOG_TAG, "no nested config object found for package=${params.packageName}")
                }
            }
        }
    }

    /**
     * 从 res/raw/legacy_xposed_hooks/ 加载配置。
     * 按 package 找到 versions.json，选最近版本，再读对应 config.json。
     */
    private fun loadConfigFromRaw(context: Context, targetPkg: String): JsonObject? {
        // 宿主进程的 resources 是宿主包的资源表，读不到 Zafiro 的 raw 资源。
        // 必须用 Zafiro 自己的包上下文（createPackageContext）去读 R.raw.*。
        val moduleContext = context.createPackageContext(XValues.myPackageName, 0)
        val versionsRawId = versionsRawIdFor(targetPkg) ?: run {
            Logger.w(LOG_TAG, "no raw config for package=$targetPkg")
            return null
        }
        val supportedVersions = readVersions(moduleContext, versionsRawId) ?: return null
        val installedVersion = context.getInstalledPackageVersion(targetPkg)?.versionCode
        if (installedVersion == null) {
            Logger.w(LOG_TAG, "cannot get installed version for package=$targetPkg")
            return null
        }
        val nearestVersion = nearestVersionCode(installedVersion, supportedVersions) ?: run {
            Logger.w(LOG_TAG, "no supported version found for package=$targetPkg")
            return null
        }
        Logger.i(
            LOG_TAG,
            "config source: installed=$installedVersion nearest=$nearestVersion supported=$supportedVersions"
        )
        val configRawId = configRawIdFor(targetPkg, nearestVersion) ?: run {
            Logger.w(LOG_TAG, "no config mapping for package=$targetPkg version=$nearestVersion")
            return null
        }
        return readConfig(moduleContext, configRawId)
    }

    private fun versionsRawIdFor(pkg: String): Int? = when (pkg) {
        "com.heytap.speechassist" -> R.raw.com_heytap_speechassist_versions
        "com.miui.voiceassist" -> R.raw.com_miui_voiceassist_versions
        else -> null
    }

    private fun configRawIdFor(pkg: String, versionCode: Long): Int? = when (pkg) {
        "com.heytap.speechassist" -> when (versionCode) {
            120803L -> R.raw.com_heytap_speechassist_120803_config
            120906L -> R.raw.com_heytap_speechassist_120906_config
            120909L -> R.raw.com_heytap_speechassist_120909_config
            else -> null
        }
        "com.miui.voiceassist" -> when (versionCode) {
            507013003L -> R.raw.com_miui_voiceassist_507013003_config
            else -> null
        }
        else -> null
    }

    private fun readVersions(context: Context, rawId: Int): List<Long>? {
        return try {
            val text = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.longOrNull }
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "failed to read versions from raw=$rawId", e)
            null
        }
    }

    private fun readConfig(context: Context, rawId: Int): JsonObject? {
        return try {
            val text = context.resources.openRawResource(rawId).bufferedReader().use { it.readText() }
            Json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "failed to read config from raw=$rawId", e)
            null
        }
    }

    private fun nearestVersionCode(requested: Long, supported: List<Long>): Long? {
        return supported.distinct().minWithOrNull(
            compareBy<Long> { kotlin.math.abs(it - requested) }.thenBy { it }
        )
    }

    private fun onSettingsFetched(
        params: XC_LoadPackage.LoadPackageParam,
        client: AgentRuntimeClient
    ) {
        // 根据 targetPkg 进行映射和 Hook 路由
        val hostApp = HostApp.fromPackageName(params.packageName)
        val hookInstance: Hook? = when (hostApp) {
            HostApp.Breeno -> BreenoChatHook(scope, client)
            HostApp.XiaoAi -> XiaoaiChatHook(scope, client)
            else -> null
        }
        Logger.i(
            LOG_TAG,
            "hook route hostApp=${hostApp?.name ?: "unknown"} " +
                    "hook=${hookInstance?.name ?: "none"}"
        )

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
