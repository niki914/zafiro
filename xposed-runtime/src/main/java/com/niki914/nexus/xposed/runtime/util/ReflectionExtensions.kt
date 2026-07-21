package com.niki914.nexus.xposed.runtime.util

import com.niki914.nexus.xposed.api.util.xTry
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method


inline fun <reified T> Any.getField(fieldName: String): T? = xTry("getField:$fieldName") {
    XposedHelpers.getObjectField(this, fieldName) as? T
}

fun Any.getAllFields(): List<Field> = xTry("getAllFields") {
    val fields = mutableListOf<Field>()
    var clazz: Class<*>? = this.javaClass

    while (clazz != null && clazz != Any::class.java && clazz != Object::class.java) {
        clazz.declaredFields.forEach { field ->
            xTry("setAccessible:${field.name}") { field.isAccessible = true }
            fields.add(field)
        }
        clazz = clazz.superclass
    }
    fields
} ?: emptyList()

inline fun <reified T> Any.call(methodName: String, vararg params: Any?): T? =
    xTry("call:$methodName") {
        XposedHelpers.callMethod(this, methodName, *params) as? T
    }

fun resolveClass(
    typeName: String,
    lpparam: XC_LoadPackage.LoadPackageParam
): Class<*>? = primitiveTypes[typeName] ?: xTry("resolveClass:$typeName") {
    Class.forName(typeName, false, lpparam.classLoader)
}

fun resolveParamTypes(
    typeNames: List<String>,
    lpparam: XC_LoadPackage.LoadPackageParam
): Array<Class<*>>? = xTry("resolveParamTypes") {
    val resolved = mutableListOf<Class<*>>()
    typeNames.forEach { typeName ->
        val clazz = resolveClass(typeName, lpparam) ?: return@xTry null
        resolved += clazz
    }
    resolved.toTypedArray()
}

fun methodSignature(method: Method): String = xTry("methodSignature:${method.name}") {
    val params = method.parameterTypes.joinToString(",") { it.name }
    "${method.returnType.name} ${method.name}($params)"
} ?: ""

fun constructorSignature(constructor: Constructor<*>): String = xTry("constructorSignature") {
    val params = constructor.parameterTypes.joinToString(",") { it.name }
    "${constructor.declaringClass.name}($params)"
} ?: ""

fun Any.setTag(key: String, value: Any?) = xTry("setTag:$key") {
    XposedHelpers.setAdditionalInstanceField(this, key, value)
}

inline fun <reified T> Any.getTag(key: String): T? = xTry("getTag:$key") {
    XposedHelpers.getAdditionalInstanceField(this, key) as? T
}

private val primitiveTypes = mapOf(
    "boolean" to Boolean::class.javaPrimitiveType!!,
    "byte" to Byte::class.javaPrimitiveType!!,
    "char" to Char::class.javaPrimitiveType!!,
    "short" to Short::class.javaPrimitiveType!!,
    "int" to Int::class.javaPrimitiveType!!,
    "long" to Long::class.javaPrimitiveType!!,
    "float" to Float::class.javaPrimitiveType!!,
    "double" to Double::class.javaPrimitiveType!!,
    "void" to Void.TYPE
)
