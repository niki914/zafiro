package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.hooks.Hooks
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.transport.HttpRequest
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeRevolverPick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * r22c B4：轮值路由 hook（每次 transport 发送尝试前调用）。
 *
 * 会话打开时端点/密钥/模型钉死在 [com.niki914.okia.Okia] 快照上，轮值冷却因此
 * 只在一回合开头生效（用户报的「delay 完全没生效」）。本 hook 在每次发送尝试前
 * 向 pickRevolverCandidate 取当前应发到的候选，把请求重指到它——endpoint、
 * 认证头（按目标协议族）、body 顶层 model 字段。
 *
 * r23：候选一律立即发送（[RuntimeRevolverPick.waitMs] 恒 0）；间隔改由「发送后冷却」
 * 门控下一次选取。`beforeRequest` 记录本次选中的候选（[lastPick]），`afterRequest`
 * （请求刚打出去的时刻，见 okia Hooks）调 [RuntimeSettingsGateway.revolverMarkSent]
 * 把该配置的解锁点登记为 now + delaySeconds*1000 并标记回答未决——计时器门控的是
 * 「下一个请求」，从不等当前这次。轮值关闭 / 候选不足 / 无候选（null）时保持原请求不动。
 */
internal object RevolverRouterHook : Hooks {

    private const val LOG_TAG = "niki914_zafiro_RevolverRouter"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * r23：最近一次 [beforeRequest] 选中的轮值候选。`afterRequest`（发送完成时）据此取
     * configId 调 [RuntimeSettingsGateway.revolverMarkSent]。同一候选无改写（同源）时也记录——
     * 它仍是「本次实际选中的模型」，冷却要照常启动。volatile：beforeRequest 与 afterRequest
     * 可能在不同线程（httpEngine 派发 vs 完成回调）触达。
     */
    @Volatile
    private var lastPick: RuntimeRevolverPick? = null

    override suspend fun beforeRequest(request: HttpRequestHolder) {
        val pick = try {
            RuntimeEnvironment.awaitSettingsGateway().pickRevolverCandidate()
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "revolver pick failed, keep original request: ${e.message}")
            return
        }
        if (pick == null) return
        lastPick = pick
        val original = request.request
        val newHeaders = authHeaders(pick, original)
        val newBody = modelBody(original, pick)
        if (newHeaders == original.headers && newBody == original.body && original.url == pick.endpoint) {
            // 候选与原请求同源：不改写（不记签名、不打日志），但冷却已在 afterRequest 启动。
            return
        }
        request.write(
            original.copy(url = pick.endpoint, headers = newHeaders, body = newBody),
            "revolver",
        )
        Logger.d(
            LOG_TAG,
            "revolver reroute model=${pick.model} protocol=${pick.protocol} label=${pick.label}",
        )
    }

    /**
     * r23：请求刚打出去的时刻（okia 在 [com.niki914.okia.transport.HttpRequest] 经
     * httpEngine 派发后调用）。启动「发送后冷却」：取本次选中候选的冷却秒数（真实值）
     * 交给网关登记解锁点 + 回答未决标记。`lastPick == null`（轮值关闭/候选不足）时 no-op。
     */
    override suspend fun afterRequest(request: HttpRequest) {
        val pick = lastPick ?: return
        try {
            val gateway = RuntimeEnvironment.awaitSettingsGateway()
            val delaySeconds = gateway.revolverDelaySeconds(pick.configId)
            gateway.revolverMarkSent(pick.configId, delaySeconds)
            Logger.d(
                LOG_TAG,
                "revolver post-send cooldown model=${pick.model} delaySeconds=$delaySeconds",
            )
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "revolver mark-sent failed, cooldown not armed: ${e.message}")
        }
    }

    /**
     * r27 F17：本次 LLM 请求的流已完整收集成功（助手消息 commit 后，每 tool-loop 段
     * 一次；一次回合最多 N 次）。按本次实际选中的候选（[lastPick]）调
     * [RuntimeSettingsGateway.revolverAnswerReceived] 抬起「属主 stage 的答案门」，
     * 使 [RuntimeSettingsGateway.pickRevolverCandidate] 的下一个收割判定满足。
     *
     * `lastPick == null`（轮值关闭/候选不足）时 no-op。调用幂等：属主专属 + 安全网
     * （[LlmStreamPipeline] 回合终态 finally）重放都无害。catch Exception 而非重抛——
     * 该 hook 是观测型（不阻断请求），网关异常不应让一次成功回答的 commit 失败。
     */
    override suspend fun afterRequestStream(request: HttpRequest) {
        val pick = lastPick ?: return
        try {
            RuntimeEnvironment.awaitSettingsGateway().revolverAnswerReceived(pick.configId)
            Logger.d(
                LOG_TAG,
                "revolver post-stream answer-received model=${pick.model} configId=${pick.configId}",
            )
        } catch (e: Exception) {
            Logger.w(
                LOG_TAG,
                "revolver post-stream answer-received failed: ${e.message}",
            )
        }
    }

    /** 按目标协议族重建认证头：Anthropic 走 x-api-key + anthropic-version；OpenAI 族走 Bearer。 */
    private fun authHeaders(pick: RuntimeRevolverPick, original: HttpRequest): Map<String, String> {
        val result = original.headers.filterKeys { key ->
            !key.equals("authorization", true) && !key.equals("x-api-key", true) &&
                    !key.equals("anthropic-version", true)
        }.toMutableMap()
        if (pick.protocol == LlmProtocol.AnthropicMessages.wireId) {
            if (pick.apiKey.isNotEmpty()) result["x-api-key"] = pick.apiKey
            result["anthropic-version"] = "2023-06-01"
        } else {
            if (pick.apiKey.isNotEmpty()) result["Authorization"] = "Bearer ${pick.apiKey}"
        }
        return result
    }

    /** 把 body 顶层 "model" 替换为候选模型；解析失败时保留原 body。 */
    private fun modelBody(original: HttpRequest, pick: RuntimeRevolverPick): String? {
        val body = original.body ?: return null
        return try {
            val obj = json.parseToJsonElement(body).jsonObject.toMutableMap()
            obj["model"] = JsonPrimitive(pick.model)
            json.encodeToString(obj)
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "revolver body model patch failed, keep original body: ${e.message}")
            body
        }
    }
}
