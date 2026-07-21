package com.niki914.nexus.xposed.runtime.util

import com.niki914.nexus.xposed.api.util.xlog
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage


fun Class<*>.inspect(): String {
    val sb = StringBuilder()

    // Class Name
    sb.append("classname: ${this.name}\n")
    sb.append("---\n")

    // Members (Fields)
    val fields = this.declaredFields
    sb.append("members: ${fields.size}\n")
    fields.forEach { field ->
        field.isAccessible = true
        val type = field.type
        sb.append("- ${field.name}: ${type.simpleName} / ${type.name}\n")
    }
    sb.append("---\n")

    // Methods
    val methods = this.declaredMethods
    sb.append("methods: ${methods.size}\n\n")
    methods.forEach { method ->
        method.isAccessible = true
        sb.append("fun ${method.name}(\n")

        val params = method.parameterTypes
        params.forEachIndexed { index, paramType ->
            val suffix = if (index == params.size - 1) "" else ","
            sb.append("    param${index + 1}: ${paramType.simpleName} / ${paramType.name}$suffix\n")
        }

        val returnType = method.returnType
        sb.append("): ${returnType.simpleName} / ${returnType.name}\n\n")
    }

    return sb.toString()
}

fun XC_LoadPackage.LoadPackageParam.inspectClass(className: String) {
    runCatching {
        val clazz = XposedHelpers.findClass(className, classLoader)
        xlog(clazz.inspect())
    }.onFailure {
        xlog("Inspect failed for $className: ${it.message}")
    }
}

fun Any?.inspectInstance(): String {
    if (this == null) return "Object is null"

    val sb = StringBuilder()
    val clazz = this::class.java

    // Header
    sb.append("instance_of: ${clazz.name}\n")
    sb.append("identity_hash: ${Integer.toHexString(System.identityHashCode(this))}\n")
    sb.append("---\n")

    // Fields with Values
    val fields = clazz.declaredFields
    sb.append("fields_with_values: ${fields.size}\n")
    fields.forEach { field ->
        runCatching {
            field.isAccessible = true
            val value = field.get(this)
            val type = field.type
            sb.append("- ${field.name} (${type.simpleName}): $value\n")
        }.onFailure {
            sb.append("- ${field.name}: [Access Failed - ${it.message}]\n")
        }
    }
    sb.append("---\n")

    // Methods (复用 Class 检查逻辑)
    sb.append(clazz.inspectMethodsOnly())

    return sb.toString()
}

private fun Class<*>.inspectMethodsOnly(): String {
    val sb = StringBuilder()
    val methods = this.declaredMethods
    sb.append("methods: ${methods.size}\n")
    methods.forEach { method ->
        method.isAccessible = true
        val params = method.parameterTypes.joinToString(", ") { it.simpleName }
        sb.append("- fun ${method.name}($params): ${method.returnType.simpleName}\n")
    }
    return sb.toString()
}