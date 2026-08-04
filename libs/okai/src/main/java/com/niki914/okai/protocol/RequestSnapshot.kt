package com.niki914.okai.protocol

import com.niki914.okai.tool.ToolDescriptor
import com.niki914.okai.transport.HttpTimeouts

/**
 * Immutable snapshot of one model call's inputs, frozen at segment start so
 * retries reuse identical requests. Built by the loop from config plus turn options.
 *
 * Design source: independent design; frozen-snapshot pattern validated in the
 * Nexus usage of kai, per kai PRD section 4.3.
 */
data class RequestSnapshot(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String?,
    val temperature: Float,
    val maxTokens: Int,
    val headers: Map<String, String>,
    val timeouts: HttpTimeouts,
    val tools: List<ToolDescriptor>
)
