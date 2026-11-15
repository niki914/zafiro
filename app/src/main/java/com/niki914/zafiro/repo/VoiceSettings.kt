package com.niki914.zafiro.repo

import android.content.Context
import com.tricreta.zafiro.voice.CloudStt
import com.tricreta.zafiro.voice.CloudSttConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** Which engine turns speech into text. DEVICE is free and built in; the others record audio and send it to a Whisper-style service. */
enum class VoiceEngine(val label: String) {
    DEVICE("This phone (free, built in)"),
    GROQ("Groq Whisper (fast, free tier, needs a key)"),
    OPENAI("OpenAI Whisper (needs a key)"),
    CUSTOM("Custom OpenAI-compatible service"),
}

data class VoiceSettingsData(val engine: VoiceEngine = VoiceEngine.DEVICE, val apiKey: String = "", val baseUrl: String = "", val model: String = "", val language: String = "") {
    /** Null means "use the phone's own recogniser" (also when a cloud engine is chosen but not fully set up). */
    fun cloudConfig(): CloudSttConfig? {
        if (engine == VoiceEngine.DEVICE || apiKey.isBlank()) return null
        val (url, mdl) = when (engine) {
            VoiceEngine.GROQ -> CloudStt.GROQ_URL to CloudStt.GROQ_MODEL
            VoiceEngine.OPENAI -> CloudStt.OPENAI_URL to CloudStt.OPENAI_MODEL
            else -> baseUrl to model
        }
        val u = baseUrl.takeIf { engine == VoiceEngine.CUSTOM }?.ifBlank { null } ?: url
        val m = model.ifBlank { mdl }
        if (u.isBlank() || m.isBlank()) return null
        return CloudSttConfig(u, apiKey.trim(), m, language.trim().ifBlank { null })
    }
}

/** Stored as plain JSON in files/settings/voice.json (same trust level as the other API keys in this app). */
object VoiceSettings {
    private fun file(context: Context) = File(context.filesDir, "settings/voice.json")

    fun read(context: Context): VoiceSettingsData = runCatching {
        val o = Json.parseToJsonElement(file(context).readText()) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        VoiceSettingsData(runCatching { VoiceEngine.valueOf(s("engine")) }.getOrDefault(VoiceEngine.DEVICE), s("api_key"), s("base_url"), s("model"), s("language"))
    }.getOrDefault(VoiceSettingsData())

    fun write(context: Context, d: VoiceSettingsData) {
        val f = file(context).also { it.parentFile?.mkdirs() }
        f.writeText(JsonObject(mapOf("engine" to JsonPrimitive(d.engine.name), "api_key" to JsonPrimitive(d.apiKey), "base_url" to JsonPrimitive(d.baseUrl), "model" to JsonPrimitive(d.model), "language" to JsonPrimitive(d.language))).toString())
    }
}
