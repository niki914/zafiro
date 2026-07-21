package com.niki914.nexus.xposed.runtime.util

import com.niki914.nexus.xposed.api.util.xlog
import com.niki914.nexus.xposed.api.xevent.XEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


fun XC_LoadPackage.LoadPackageParam.findClass(name: String): Class<*>? =
    hookExtensionTry(this, name) { XposedHelpers.findClass(name, this.classLoader) }

fun XC_LoadPackage.LoadPackageParam.hookMethod(
    className: String,
    methodName: String,
    vararg params: Any?,
    before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    after: (XC_MethodHook.MethodHookParam) -> Unit = {},
    onError: ((Throwable?) -> Unit)? = null
) {
    val hookName = "$className#$methodName"
    hookExtensionTry(this, hookName, onError) {
        val clazz = findClass(className) ?: return@hookExtensionTry
        val hookParams = arrayOf(*params, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                hookExtensionTry(this@hookMethod, hookName, onError) { before(param) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                hookExtensionTry(this@hookMethod, hookName, onError) { after(param) }
            }
        })
        XposedHelpers.findAndHookMethod(clazz, methodName, *hookParams)
    }
}

fun XC_LoadPackage.LoadPackageParam.hookConstructor(
    className: String,
    vararg params: Any?,
    before: (XC_MethodHook.MethodHookParam) -> Unit = {},
    after: (XC_MethodHook.MethodHookParam) -> Unit = {},
    onError: ((Throwable?) -> Unit)? = null
) {
    val hookName = "$className#CONSTRUCTOR"
    hookExtensionTry(this, hookName, onError) {
        val clazz = findClass(className) ?: return@hookExtensionTry
        val hookParams = arrayOf(*params, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                hookExtensionTry(this@hookConstructor, hookName, onError) { before(param) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                hookExtensionTry(this@hookConstructor, hookName, onError) { after(param) }
            }
        })
        XposedHelpers.findAndHookConstructor(clazz, *hookParams)
    }
}

fun hookKey(
    stage: String,
    className: String,
    methodName: String,
    paramTypes: List<Class<*>>
): String = hookExtensionTry(null, "hookKey:$className#$methodName") {
    "$stage:$className#$methodName(${paramTypes.joinToString(",") { it.name }})"
} ?: ""

fun constructorHookKey(
    stage: String,
    className: String,
    paramTypes: List<Class<*>>
): String = hookExtensionTry(null, "constructorHookKey:$className") {
    "$stage:$className#CONSTRUCTOR(${paramTypes.joinToString(",") { it.name }})"
} ?: ""

private fun <T> hookExtensionTry(
    lpparam: XC_LoadPackage.LoadPackageParam?,
    name: String,
    onError: ((Throwable?) -> Unit)? = null,
    block: () -> T
): T? = runCatching(block).onFailure {
    XEvent.hookFailed(name, it)
    xlog("$name\n${it.stackTraceToString()}")
    val className = name.substringBefore('#').substringAfter(':')
    if (className.isNotBlank()) lpparam?.inspectClass(className)
    onError?.invoke(it)
}.getOrNull()
