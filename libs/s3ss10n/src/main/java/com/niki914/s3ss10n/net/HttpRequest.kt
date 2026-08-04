package com.niki914.s3ss10n.net

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMs: HttpTimeouts,
    val isStreaming: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpRequest

        if (method != other.method) return false
        if (url != other.url) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (timeoutMs != other.timeoutMs) return false
        if (isStreaming != other.isStreaming) return false

        return true
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + timeoutMs.hashCode()
        result = 31 * result + isStreaming.hashCode()
        return result
    }
}

data class HttpTimeouts(
    val connectMs: Long,
    val readMs: Long,
    val writeMs: Long,
)
