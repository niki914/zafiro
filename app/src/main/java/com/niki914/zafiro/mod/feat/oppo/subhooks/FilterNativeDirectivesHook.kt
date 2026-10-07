package com.niki914.zafiro.mod.feat.oppo.subhooks

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.ActiveTurnStore
import com.niki914.zafiro.chat.TurnMode
import com.niki914.zafiro.mod.feat.HookTarget
import com.niki914.zafiro.mod.feat.SubHook
import com.niki914.zafiro.mod.feat.oppo.BreenoConfigProvider
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONArray
import org.json.JSONObject

/**
 * 在 InjectedLLM 模式下拦截并过滤小布（Breeno）云端入站的 Directives（指令），从源头消除不该露出的原生内容。
 *
 * 拦截范围：
 * - Recommend (相关内容推荐卡、猜你想问等)
 * - SpeechSynthesizer (官方 TTS 语音播报，避免与 LLM 声音冲突)
 * - PhoneManager, SystemStorage, System, App (清理后台、跳应用等系统技能)
 * - MyAI (LoadingStateCard, StreamTextCard 原生卡片)
 * - Tracking (BreenoFeedback 点赞点踩卡)
 * - SpeechRecognizer (ExpectSpeech)
 *
 * 行为：
 * - InjectedLLM: 若指令全部属于被拦截类型，直接 short-circuit (param.result = null)；
 *   若部分被过滤，重写 messageContent 参数仅保留非拦截指令。
 * - NativeTakeover 或 无状态: 保守放行。
 */
class FilterNativeDirectivesHook(
    private val selfInjectedFlagKey: String = BreenoConfigProvider.CaptureResponseTarget.selfInjectedFlagKey
) : SubHook() {

    private companion object {
        const val LOG_TAG = "niki914_zafiro_FilterDirectives"
    }

    override val hookTarget: HookTarget?
        get() = BreenoConfigProvider.FilterNativeDirectives.hookTarget

    override fun beforeHook(param: XC_MethodHook.MethodHookParam) {
        val activeTurn = ActiveTurnStore.getCurrent()
        when (activeTurn?.mode) {
            TurnMode.InjectedLLM -> Unit
            TurnMode.NativeTakeover, null -> {
                Logger.d(
                    LOG_TAG,
                    "native directives pass host=breeno source=$name reason=takeover_${activeTurn?.mode}"
                )
                return
            }
        }

        val contentIndex = BreenoConfigProvider.FilterNativeDirectives.messageContentArgIndex
        val rawContent = param.args.getOrNull(contentIndex) as? String
        if (rawContent.isNullOrBlank()) return

        try {
            val json = JSONObject(rawContent)
            // 若包含自身注入标记，直接放行
            if (json.optJSONObject("extend")?.optString(selfInjectedFlagKey) == "true") {
                Logger.d(LOG_TAG, "native directives pass host=breeno source=$name reason=self_injected")
                return
            }

            val directives = json.optJSONArray("directives") ?: return
            if (directives.length() == 0) return

            val kept = JSONArray()
            var removed = 0

            for (i in 0 until directives.length()) {
                val directive = directives.optJSONObject(i)
                if (directive == null) {
                    kept.put(directives.opt(i))
                    continue
                }
                val header = directive.optJSONObject("header")
                val namespace = header?.optString("namespace").orEmpty()
                val name = header?.optString("name").orEmpty()

                if (shouldSuppress(namespace, name)) {
                    removed++
                    Logger.d(
                        LOG_TAG,
                        "suppressed directive host=breeno namespace=$namespace name=$name"
                    )
                } else {
                    kept.put(directive)
                }
            }

            if (removed == 0) return

            Logger.i(
                LOG_TAG,
                "native directives filtered host=breeno removed=$removed kept=${kept.length()}"
            )

            if (kept.length() == 0) {
                // 全部指令均被过滤，直接截断整个入站方法执行
                param.result = null
            } else {
                // 部分保留，重写入站 JSON 参数
                json.put("directives", kept)
                param.args[contentIndex] = json.toString()
            }
        } catch (e: Throwable) {
            Logger.e(LOG_TAG, "failed to filter inbound directives: ${e.message}", e)
        }
    }

    private fun shouldSuppress(namespace: String, name: String): Boolean = when (namespace) {
        "Recommend" -> true
        "SpeechSynthesizer" -> true
        "PhoneManager",
        "SystemStorage",
        "System",
        "SystemScreen",
        "App",
        "Sms",
        "PhoneCall",
        "Ocr",
        "AnalogClick" -> true
        "MyAI" -> name == "LoadingStateCard" || name == "StreamTextCard"
        "Tracking" -> name == "BreenoFeedback"
        "SpeechRecognizer" -> name == "ExpectSpeech"
        else -> false
    }
}
