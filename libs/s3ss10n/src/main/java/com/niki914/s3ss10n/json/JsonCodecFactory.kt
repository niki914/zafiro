package com.niki914.s3ss10n.json

import com.niki914.s3ss10n.ext.json.GsonJsonCodec

object JsonCodecFactory {
    fun create(): JsonCodec = GsonJsonCodec()
}
