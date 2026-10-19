// githooks:ignore-file bare-catch 反射层静默探测：捕获所有反射异常
package com.niki914.xposed.api.util

import kotlinx.coroutines.CancellationException
import java.lang.reflect.Field


fun dumpObjectShape(
    obj: Any?,
    maxCollectionSample: Int
): Map<String, Any?> {
    if (obj == null) {
        return mapOf("present" to false)
    }

    val clazz = obj.javaClass
    val fields = allFieldsOf(clazz).map { field ->
        val value = try {
            field.isAccessible = true
            field.get(obj)
        } catch (error: Throwable) {
            return@map mapOf(
                "name" to field.name,
                "type" to field.type.name,
                "valueSummary" to "<error:${error.message}>"
            )
        }

        mapOf(
            "name" to field.name,
            "type" to field.type.name,
            "valueSummary" to summarizeValue(value, maxCollectionSample),
            "listElementTypes" to collectionElementTypes(value, maxCollectionSample)
        )
    }

    return mapOf(
        "present" to true,
        "className" to clazz.name,
        "classLoader" to describeClassLoader(clazz.classLoader),
        "interfaces" to clazz.interfaces.map { it.name },
        "superClasses" to superClassesOf(clazz),
        "fields" to fields
    )
}

fun summarizeValue(
    value: Any?,
    maxCollectionSample: Int
): Any? {
    return when (value) {
        null -> null
        is String, is Number, is Boolean, is Char -> value
        is Collection<*> -> mapOf(
            "type" to value.javaClass.name,
            "size" to value.size,
            "sampleElementTypes" to collectionElementTypes(value, maxCollectionSample)
        )

        is Map<*, *> -> mapOf(
            "type" to value.javaClass.name,
            "size" to value.size,
            "sampleKeyTypes" to value.keys.filterNotNull().take(maxCollectionSample)
                .map { it.javaClass.name },
            "sampleValueTypes" to value.values.filterNotNull().take(maxCollectionSample)
                .map { it.javaClass.name }
        )

        is Array<*> -> mapOf(
            "type" to value.javaClass.name,
            "size" to value.size,
            "sampleElementTypes" to value.filterNotNull().take(maxCollectionSample)
                .map { it.javaClass.name }
        )

        else -> "${value.javaClass.name}@${System.identityHashCode(value)}"
    }
}

fun collectionElementTypes(
    value: Any?,
    maxCollectionSample: Int
): List<String> {
    return when (value) {
        is Collection<*> -> value.filterNotNull()
            .take(maxCollectionSample)
            .map { it.javaClass.name }
            .distinct()

        is Array<*> -> value.filterNotNull()
            .take(maxCollectionSample)
            .map { it.javaClass.name }
            .distinct()

        else -> emptyList()
    }
}

fun describeClassLoader(classLoader: ClassLoader?): String {
    if (classLoader == null) return "bootstrap"
    return "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
}

fun allFieldsOf(clazz: Class<*>): List<Field> =
    inspectOrNull {
        val fields = mutableListOf<Field>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            fields += current.declaredFields
            current = current.superclass
        }
        fields
    } ?: emptyList()

fun superClassesOf(clazz: Class<*>): List<String> = inspectOrNull {
    val chain = mutableListOf<String>()
    var current: Class<*>? = clazz.superclass
    while (current != null && current != Any::class.java) {
        chain += current.name
        current = current.superclass
    }
    chain
} ?: emptyList()

fun findField(
    clazz: Class<*>,
    fieldName: String
): Field? = allFieldsOf(clazz).firstOrNull { it.name == fieldName }

private inline fun <R> inspectOrNull(block: () -> R): R? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}
