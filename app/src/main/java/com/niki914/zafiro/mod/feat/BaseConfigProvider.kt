package com.niki914.zafiro.mod.feat

import com.niki914.logging.Logger
import com.niki914.xposed.api.util.xTry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 抽象的配置提供者基类，用于规范各个语音助手的配置包装器
 *
 * 当前配置从 res/raw/legacy_xposed_hooks/ 加载，由 Entrance 注入 BaseConfigProvider.config。
 * 未来若恢复网络/远程配置源，只需改写此处 getElement() 的实现。
 */
abstract class BaseConfigProvider {

    companion object {
        const val LOG_TAG = "niki914_nexus_BaseConfigProvider"

        // 由 Entrance.kt 在拿到 raw 配置后注入。
        // TODO 未来切回远程配置源时，改回 XRepo.web.await().configOrNull()。
        @Volatile
        var config: JsonObject? = null
    }

    private fun getElement(path: String): JsonElement? {
        val currentConfig = config ?: return null
        var current: JsonElement? = currentConfig[path.substringBefore(".")]
        path.substringAfter(".", "").takeIf { it.isNotEmpty() }?.split(".")?.forEach { key ->
            current = (current as? JsonObject)?.get(key)
        }
        return current
    }

    fun getString(path: String): String =
        getElement(path)?.jsonPrimitive?.contentOrNull.orThrowException(path)

    fun getBoolean(path: String): Boolean =
        getElement(path)?.jsonPrimitive?.booleanOrNull.orThrowException(path)

    fun getInt(path: String): Int =
        getElement(path)?.jsonPrimitive?.intOrNull.orThrowException(path)

    fun getList(path: String): List<JsonElement> =
        ((getElement(path) as? JsonArray)?.toList()).orThrowException(path)

    fun getObject(path: String): JsonObject =
        (getElement(path) as? JsonObject).orThrowException(path)

    fun parseHookTarget(path: String): HookTarget? =
        xTry("BaseConfigProvider.parseHookTarget:$path") {
            val ownerClass = getString("$path.owner_class")
            val methodName = getString("$path.method_name")
            val methodParams = getList("$path.param_types")
                .mapNotNull { it.jsonPrimitive.contentOrNull }
            val hookTiming = getString("$path.hook_timing")
            val returnType = getString("$path.return_type")
            HookTarget(ownerClass, methodName, methodParams, hookTiming, returnType)
        }

    protected fun <T : Any> T?.orThrowException(path: String): T {
        if (this == null) {
            Logger.w(LOG_TAG, "config path not resolved: $path")
            throw IllegalStateException("path not resolved : $path")
        }
        return this
    }

}
