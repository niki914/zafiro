package com.niki914.okia.conversation

import kotlinx.serialization.json.Json

/**
 * SessionCodec 默认实现：kotlinx.serialization JSON。
 * 交换格式 = SessionSnapshot 的默认 JSON 编码（§5.13 JsonCodec 删除后
 * 协议无关 dataclass 直接 @Serializable）；decode 遇非法输入抛异常
 * （SerializationException），快速失败不静默修复。
 * Design source: okia PRD §5.3 / §5.13。
 */
class JsonSessionCodec(private val json: Json = Json) : SessionCodec {

    // 快照 → 交换格式
    override fun encode(snapshot: SessionSnapshot): String =
        json.encodeToString(SessionSnapshot.serializer(), snapshot)

    // 交换格式 → 快照
    override fun decode(raw: String): SessionSnapshot =
        json.decodeFromString(SessionSnapshot.serializer(), raw)
}
