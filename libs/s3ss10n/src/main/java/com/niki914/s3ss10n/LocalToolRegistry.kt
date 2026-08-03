package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodecFactory
import com.niki914.s3ss10n.json.JsonCodec

enum class ToolValueType(val jsonType: String) {
    String("string"),
    Integer("integer"),
    Number("number"),
    Boolean("boolean"),
    Object("object"),
    Array("array")
}

data class LocalToolProperty(
    val name: String,
    var type: ToolValueType = ToolValueType.String,
    var description: String = "",
    var required: Boolean = false,
    var enumValues: List<String> = emptyList()
)

data class LocalToolConfig(
    var description: String = "",
    var rawInputSchemaJson: String? = null
) {
    internal val properties = mutableMapOf<String, LocalToolProperty>()
    internal val requiredNames = mutableListOf<String>()

    fun string(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.String, block)
    }

    fun integer(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Integer, block)
    }

    fun number(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Number, block)
    }

    fun boolean(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Boolean, block)
    }

    fun object_(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Object, block)
    }

    fun array(name: String, block: LocalToolProperty.() -> Unit = {}) {
        addProperty(name, ToolValueType.Array, block)
    }

    fun rawJsonSchema(json: String) {
        rawInputSchemaJson = json
    }

    private fun addProperty(name: String, type: ToolValueType, block: LocalToolProperty.() -> Unit) {
        val prop = LocalToolProperty(name = name, type = type).apply(block)
        properties[name] = prop
        if (prop.required) requiredNames.add(name)
    }

    internal fun toToolDescriptor(
        toolName: String,
        kind: ToolCallKind,
        codec: JsonCodec
    ): ToolDescriptor {
        return ToolDescriptor(
            name = toolName,
            description = description,
            inputSchema = buildInputSchema(toolName, codec),
            kind = kind
        )
    }

    internal fun deepCopy(): LocalToolConfig {
        val newConfig = LocalToolConfig(description, rawInputSchemaJson)
        properties.forEach { (name, prop) -> newConfig.properties[name] = prop.copy() }
        newConfig.requiredNames.addAll(requiredNames)
        return newConfig
    }

    private fun buildInputSchema(toolName: String, codec: JsonCodec): Map<String, Any?> {
        rawInputSchemaJson?.let { raw ->
            return codec.decodeMap(raw)
                ?: throw IllegalArgumentException("Invalid rawJsonSchema for tool $toolName")
        }
        val propDefs = properties.mapValues { (_, prop) ->
            buildMap<String, Any?> {
                put("type", prop.type.jsonType)
                put("description", prop.description)
                if (prop.enumValues.isNotEmpty()) {
                    put("enum", prop.enumValues)
                }
            }
        }
        return buildMap {
            put("type", "object")
            put("properties", propDefs)
            if (requiredNames.isNotEmpty()) {
                put("required", requiredNames.distinct())
            }
        }
    }
}

interface LocalToolRegistry {
    var codec: JsonCodec
    fun add(name: String, block: LocalToolConfig.() -> Unit)
    fun replace(name: String, block: LocalToolConfig.() -> Unit)
    fun remove(name: String)
}

internal class LocalToolRegistryImpl : LocalToolRegistry {
    override var codec: JsonCodec = JsonCodecFactory.create()
    private val _tools = mutableMapOf<String, LocalToolConfig>()

    val tools: Map<String, LocalToolConfig> get() = _tools.toMap()

    override fun add(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun replace(name: String, block: LocalToolConfig.() -> Unit) {
        _tools[name] = LocalToolConfig().apply(block)
    }

    override fun remove(name: String) {
        _tools.remove(name)
    }

    fun toToolDescriptors(codec: JsonCodec): List<ToolDescriptor> =
        _tools.map { (name, config) ->
            config.toToolDescriptor(name, ToolCallKind.Local, codec)
        }

    internal fun copyFrom(other: LocalToolRegistryImpl) {
        codec = other.codec
        _tools.clear()
        other._tools.forEach { (k, v) ->
            _tools[k] = v.deepCopy()
        }
    }
}
