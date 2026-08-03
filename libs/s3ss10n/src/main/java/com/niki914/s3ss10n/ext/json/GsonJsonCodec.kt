package com.niki914.s3ss10n.ext.json

import com.google.gson.Gson
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.xTry

class GsonJsonCodec(private val gson: Gson = Gson()) : JsonCodec {
    override fun encode(value: Any?): String = gson.toJson(value)

    override fun <T : Any> decode(json: String, type: Class<T>): T? = xTry("GsonJsonCodec.decode", false) {
        gson.fromJson(json, type)
    }

    override fun decodeMap(json: String): Map<String, Any?>? = xTry("GsonJsonCodec.decodeMap", false) {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(json, Map::class.java) as? Map<String, Any?>
    }

    override fun decodeList(json: String): List<Any?>? = xTry("GsonJsonCodec.decodeList", false) {
        @Suppress("UNCHECKED_CAST")
        gson.fromJson(json, List::class.java) as? List<Any?>
    }
}
