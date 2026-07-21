package com.niki914.nexus.agentic.mod.feat

import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.xposed.api.util.xTry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 抽象的配置提供者基类，用于规范各个语音助手的配置包装器
 */
abstract class BaseConfigProvider {
    private fun getElement(path: String): JsonElement? = runBlocking {
        val config = XRepo.web.await().configOrNull()
        var current: JsonElement? = config?.get(path.substringBefore("."))
        path.substringAfter(".", "").takeIf { it.isNotEmpty() }?.split(".")?.forEach { key ->
            current = (current as? JsonObject)?.get(key)
        }
        current
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
        return this ?: throw IllegalStateException("path not resolved : $path")
    }
}
