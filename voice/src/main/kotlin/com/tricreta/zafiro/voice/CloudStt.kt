package com.tricreta.zafiro.voice

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** An OpenAI-compatible speech-to-text service (Groq, OpenAI, or any server that offers /audio/transcriptions). */
data class CloudSttConfig(val baseUrl: String, val apiKey: String, val model: String, val language: String? = null)

/** Pure parts of cloud transcription (request building, URL rules) so they are unit-tested; the network call is a thin wrapper. */
object CloudStt {
    const val GROQ_URL = "https://api.groq.com/openai/v1"
    const val GROQ_MODEL = "whisper-large-v3-turbo"
    const val OPENAI_URL = "https://api.openai.com/v1"
    const val OPENAI_MODEL = "whisper-1"

    /** Accepts a base URL (".../v1") or the full endpoint. */
    fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/audio/transcriptions")) b else "$b/audio/transcriptions"
    }

    fun multipart(boundary: String, model: String, language: String?, fileName: String, mime: String, audio: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun text(name: String, value: String) {
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        }
        text("model", model)
        text("response_format", "text")
        text("temperature", "0")
        if (!language.isNullOrBlank()) text("language", language)
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
        out.write(audio)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        return out.toByteArray()
    }

    /** A readable explanation for the owner, from an HTTP status. */
    fun explain(code: Int, body: String): String = when (code) {
        401, 403 -> "The speech service rejected the API key (HTTP $code). Check the key in Settings > Voice input."
        404 -> "The speech service address is wrong (HTTP 404). Check Settings > Voice input."
        413 -> "The recording was too long for the speech service."
        429 -> "The speech service is rate limiting you (HTTP 429). Wait a minute."
        else -> "The speech service failed (HTTP $code). ${body.take(120)}"
    }

    /** Blocking; call off the main thread. */
    fun transcribe(config: CloudSttConfig, audio: File, mime: String = "audio/mp4"): Result<String> = runCatching {
        val boundary = "zafiro" + System.currentTimeMillis()
        val body = multipart(boundary, config.model, config.language, audio.name, mime, audio.readBytes())
        val c = URL(endpoint(config.baseUrl)).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 60_000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error(explain(code, text))
            text.trim()
        } finally { c.disconnect() }
    }
}
