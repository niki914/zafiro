package com.niki914.zafiro.app.ui.model

import com.niki914.zafiro.settings.model.LlmProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointInferenceTest {

    private val chat = LlmProtocol.OpenAiChatCompletions
    private val responses = LlmProtocol.OpenAiResponses
    private val messages = LlmProtocol.AnthropicMessages
    private val deepseek = LlmProtocol.DeepSeek

    // ── replaceSuffix：预置官方端点 × 3 个可选协议 ──────────────────────────

    @Test
    fun `replaceSuffix preset endpoints to all protocols`() {
        val cases = mapOf(
            // provider officialEndpoint to (chat, responses, messages)
            "https://api.openai.com/v1/chat/completions" to Triple(
                "https://api.openai.com/v1/chat/completions",
                "https://api.openai.com/v1/responses",
                "https://api.openai.com/v1/messages",
            ),
            "https://api.deepseek.com/responses" to Triple(
                "https://api.deepseek.com/chat/completions",
                "https://api.deepseek.com/responses",
                "https://api.deepseek.com/v1/messages",
            ),
            "https://api.anthropic.com/v1/messages" to Triple(
                "https://api.anthropic.com/v1/chat/completions",
                "https://api.anthropic.com/v1/responses",
                "https://api.anthropic.com/v1/messages",
            ),
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" to Triple(
                "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                "https://generativelanguage.googleapis.com/v1beta/openai/responses",
                "https://generativelanguage.googleapis.com/v1beta/openai/v1/messages",
            ),
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions" to Triple(
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/responses",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/messages",
            ),
            "https://api.moonshot.cn/v1/chat/completions" to Triple(
                "https://api.moonshot.cn/v1/chat/completions",
                "https://api.moonshot.cn/v1/responses",
                "https://api.moonshot.cn/v1/messages",
            ),
            "https://api.siliconflow.cn/v1/chat/completions" to Triple(
                "https://api.siliconflow.cn/v1/chat/completions",
                "https://api.siliconflow.cn/v1/responses",
                "https://api.siliconflow.cn/v1/messages",
            ),
            "https://openrouter.ai/api/v1/chat/completions" to Triple(
                "https://openrouter.ai/api/v1/chat/completions",
                "https://openrouter.ai/api/v1/responses",
                "https://openrouter.ai/api/v1/messages",
            ),
            "https://api.commandcode.ai/provider/v1/chat/completions" to Triple(
                "https://api.commandcode.ai/provider/v1/chat/completions",
                "https://api.commandcode.ai/provider/v1/responses",
                "https://api.commandcode.ai/provider/v1/messages",
            ),
            "https://opencode.ai/zen/go/v1/chat/completions" to Triple(
                "https://opencode.ai/zen/go/v1/chat/completions",
                "https://opencode.ai/zen/go/v1/responses",
                "https://opencode.ai/zen/go/v1/messages",
            ),
        )
        cases.forEach { (endpoint, expected) ->
            val (toChat, toResponses, toMessages) = expected
            assertEquals("chat: $endpoint", toChat, EndpointInference.replaceSuffix(endpoint, chat))
            assertEquals(
                "responses: $endpoint",
                toResponses,
                EndpointInference.replaceSuffix(endpoint, responses),
            )
            assertEquals(
                "messages: $endpoint",
                toMessages,
                EndpointInference.replaceSuffix(endpoint, messages),
            )
        }
    }

    @Test
    fun `replaceSuffix round trip preserves version segment`() {
        val endpoints = listOf(
            "https://api.openai.com/v1/chat/completions",
            "https://api.commandcode.ai/provider/v1/chat/completions",
            "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        )
        endpoints.forEach { endpoint ->
            val viaResponses = EndpointInference.replaceSuffix(endpoint, responses)
            assertEquals(
                endpoint,
                EndpointInference.replaceSuffix(viaResponses, chat),
            )
        }
    }

    @Test
    fun `replaceSuffix never produces doubled version segment`() {
        val endpoints = listOf(
            "https://api.openai.com/v1/chat/completions",
            "https://api.moonshot.cn/v1/chat/completions",
            "https://api.commandcode.ai/provider/v1/chat/completions",
        )
        endpoints.forEach { endpoint ->
            val result = EndpointInference.replaceSuffix(endpoint, messages)
            assertFalse("doubled /v1 in $result", result.contains("/v1/v1"))
        }
    }

    @Test
    fun `replaceSuffix custom endpoint keeps custom version`() {
        assertEquals(
            "https://proxy.example.com/v2/messages",
            EndpointInference.replaceSuffix("https://proxy.example.com/v2/chat/completions", messages),
        )
        assertEquals(
            "https://proxy.example.com/v2/chat/completions",
            EndpointInference.replaceSuffix("https://proxy.example.com/v2/responses", chat),
        )
    }

    @Test
    fun `replaceSuffix custom endpoint without known path appends`() {
        assertEquals(
            "https://proxy.example.com/api/chat/completions",
            EndpointInference.replaceSuffix("https://proxy.example.com/api", chat),
        )
        // messages 协议硬要求 /v1 前缀
        assertEquals(
            "https://proxy.example.com/api/v1/messages",
            EndpointInference.replaceSuffix("https://proxy.example.com/api", messages),
        )
    }

    @Test
    fun `replaceSuffix blank endpoint returns blank`() {
        assertEquals("", EndpointInference.replaceSuffix("", chat))
        assertEquals("", EndpointInference.replaceSuffix("   ", messages))
    }

    @Test
    fun `replaceSuffix deepseek protocol matches chat completions shape`() {
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            EndpointInference.replaceSuffix("https://api.moonshot.cn/v1/responses", deepseek),
        )
    }

    // ── endpointMatchesProtocol ─────────────────────────────────────────────

    @Test
    fun `endpointMatchesProtocol suffix rules`() {
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                "https://api.openai.com/v1/chat/completions",
                chat,
            )
        )
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                "https://api.openai.com/v1/responses",
                responses,
            )
        )
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                "https://api.anthropic.com/v1/messages",
                messages,
            )
        )
        // deepseek 协议与 chat/completions 同壳
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                "https://api.deepseek.com/chat/completions",
                deepseek,
            )
        )
        // trailing slash 容忍
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                "https://api.openai.com/v1/chat/completions/",
                chat,
            )
        )
        assertFalse(
            EndpointInference.endpointMatchesProtocol(
                "https://api.openai.com/v1/chat/completions",
                messages,
            )
        )
        assertFalse(EndpointInference.endpointMatchesProtocol("", chat))
        assertFalse(EndpointInference.endpointMatchesProtocol("   ", chat))
    }

    // ── isPresetEndpoint ────────────────────────────────────────────────────

    @Test
    fun `isPresetEndpoint recognizes all official endpoints`() {
        ProviderSpecs.all.forEach { spec ->
            assertTrue(
                "${spec.id} official endpoint should be preset",
                EndpointInference.isPresetEndpoint(spec.officialEndpoint),
            )
        }
        assertFalse(EndpointInference.isPresetEndpoint("https://my-proxy.com/v1/chat/completions"))
        assertFalse(EndpointInference.isPresetEndpoint(""))
    }

    @Test
    fun `isPresetEndpoint recognizes derived protocol forms of preset endpoints`() {
        // 静默更新派生出的其他协议形态仍是预置，切协议不弹窗
        ProviderSpecs.all.forEach { spec ->
            LlmProtocol.entries.forEach { protocol ->
                val derived = EndpointInference.replaceSuffix(spec.officialEndpoint, protocol)
                assertTrue(
                    "${spec.id} derived $derived should be preset",
                    EndpointInference.isPresetEndpoint(derived),
                )
            }
        }
    }

    @Test
    fun `isPresetEndpoint rejects custom endpoints sharing known api tails`() {
        // 不同 host 的自定义代理不应误判为预置
        assertFalse(
            EndpointInference.isPresetEndpoint("https://proxy.example.com/v1/responses"),
        )
        // 相同 host 但品牌路径段不同的自定义端点也不误判
        assertFalse(
            EndpointInference.isPresetEndpoint("https://api.commandcode.ai/other/v1/chat/completions"),
        )
    }

    @Test
    fun `openai spec endpoint matches its default protocol`() {
        // OpenAI officialEndpoint 与 defaultProtocol 必须自洽，否则新建即不匹配
        val openai = ProviderSpecs.find("openai")
        assertTrue(
            EndpointInference.endpointMatchesProtocol(
                openai.officialEndpoint,
                LlmProtocol.fromWire(openai.defaultProtocol),
            )
        )
    }
}
