package com.niki914.kai.json

import com.niki914.kai.ext.json.GsonJsonCodec

object JsonCodecFactory {
    fun create(): JsonCodec = GsonJsonCodec()
}
