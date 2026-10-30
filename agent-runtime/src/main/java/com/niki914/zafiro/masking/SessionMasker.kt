package com.niki914.zafiro.masking

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.settings.model.RuntimeMaskingRule

class SessionMasker {

    private data class MatchedSpan(
        val start: Int,
        val end: Int,
        val raw: String,
        val kind: String,
    )

    private data class CompiledPattern(
        val kind: String,
        val trimmed: String,
        val regex: Regex?,
    )

    fun mask(text: String, rules: List<RuntimeMaskingRule>): String {
        val compiledPatterns = compilePatterns(rules)
        if (text.isEmpty() || compiledPatterns.isEmpty()) return text
        return maskText(text, compiledPatterns, mutableMapOf(), mutableMapOf())
    }

    fun maskHistory(
        history: List<Message>,
        rules: List<RuntimeMaskingRule>,
    ): List<Message> {
        val compiledPatterns = compilePatterns(rules)
        if (history.isEmpty() || compiledPatterns.isEmpty()) return history

        val rawToPlaceholder = mutableMapOf<String, String>()
        val kindCounters = mutableMapOf<String, Int>()

        fun mask(text: String): String =
            maskText(text, compiledPatterns, rawToPlaceholder, kindCounters)

        return history.map { message ->
            when (message) {
                is Message.User -> {
                    var contentChanged = false
                    val newContent = message.content.map { block ->
                        if (block is ContentBlock.Text) {
                            val masked = mask(block.text)
                            if (masked != block.text) {
                                contentChanged = true
                                block.copy(text = masked)
                            } else {
                                block
                            }
                        } else {
                            block
                        }
                    }
                    if (contentChanged) message.copy(content = newContent) else message
                }

                is Message.Assistant -> {
                    var contentChanged = false
                    val newContent = message.message.content.map { block ->
                        when (block) {
                            is ContentBlock.Text -> {
                                val masked = mask(block.text)
                                if (masked != block.text) {
                                    contentChanged = true
                                    block.copy(text = masked)
                                } else {
                                    block
                                }
                            }

                            is ContentBlock.ToolCall -> {
                                val masked = mask(block.argumentsJson)
                                if (masked != block.argumentsJson) {
                                    contentChanged = true
                                    block.copy(argumentsJson = masked)
                                } else {
                                    block
                                }
                            }

                            else -> block
                        }
                    }
                    if (contentChanged) {
                        message.copy(message = message.message.copy(content = newContent))
                    } else {
                        message
                    }
                }

                is Message.ToolResult -> {
                    when (val outcome = message.outcome) {
                        is ToolCallOutcome.Success -> {
                            val masked = mask(outcome.content)
                            if (masked != outcome.content) {
                                message.copy(outcome = outcome.copy(content = masked))
                            } else {
                                message
                            }
                        }

                        is ToolCallOutcome.Failure -> {
                            val maskedMsg = mask(outcome.message)
                            val maskedContent = outcome.content?.let { mask(it) }
                            if (maskedMsg != outcome.message || maskedContent != outcome.content) {
                                message.copy(
                                    outcome = outcome.copy(
                                        message = maskedMsg,
                                        content = maskedContent,
                                    )
                                )
                            } else {
                                message
                            }
                        }

                        is ToolCallOutcome.Intercepted -> {
                            val maskedReason = mask(outcome.reason)
                            val maskedContent = outcome.content?.let { mask(it) }
                            if (maskedReason != outcome.reason || maskedContent != outcome.content) {
                                message.copy(
                                    outcome = outcome.copy(
                                        reason = maskedReason,
                                        content = maskedContent,
                                    )
                                )
                            } else {
                                message
                            }
                        }

                        is ToolCallOutcome.Interrupted -> {
                            val maskedContent = outcome.content?.let { mask(it) }
                            if (maskedContent != outcome.content) {
                                message.copy(outcome = outcome.copy(content = maskedContent))
                            } else {
                                message
                            }
                        }

                        is ToolCallOutcome.Unknown -> {
                            val maskedMsg = mask(outcome.message)
                            val maskedContent = outcome.content?.let { mask(it) }
                            if (maskedMsg != outcome.message || maskedContent != outcome.content) {
                                message.copy(
                                    outcome = outcome.copy(
                                        message = maskedMsg,
                                        content = maskedContent,
                                    )
                                )
                            } else {
                                message
                            }
                        }
                    }
                }
            }
        }
    }

    private fun compilePatterns(rules: List<RuntimeMaskingRule>): List<CompiledPattern> {
        return rules.filter { it.enabled && it.patterns.isNotEmpty() }.flatMap { rule ->
            rule.patterns.mapNotNull { pattern ->
                val trimmed = pattern.trim()
                if (trimmed.isEmpty()) null
                else {
                    val regex = try {
                        Regex(trimmed)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    CompiledPattern(rule.kind, trimmed, regex)
                }
            }
        }
    }

    private fun maskText(
        text: String,
        compiledPatterns: List<CompiledPattern>,
        rawToPlaceholder: MutableMap<String, String>,
        kindCounters: MutableMap<String, Int>,
    ): String {
        if (text.isEmpty() || compiledPatterns.isEmpty()) return text

        val spans = mutableListOf<MatchedSpan>()
        for (pattern in compiledPatterns) {
            val regex = pattern.regex
            if (regex != null) {
                for (m in regex.findAll(text)) {
                    val raw = m.value
                    if (raw.isNotEmpty()) {
                        spans.add(MatchedSpan(m.range.first, m.range.last + 1, raw, pattern.kind))
                    }
                }
            } else {
                var index = 0
                while (index < text.length) {
                    val pos = text.indexOf(pattern.trimmed, index, ignoreCase = true)
                    if (pos < 0) break
                    val end = pos + pattern.trimmed.length
                    val raw = text.substring(pos, end)
                    spans.add(MatchedSpan(pos, end, raw, pattern.kind))
                    index = end
                }
            }
        }

        if (spans.isEmpty()) return text

        spans.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))

        val nonOverlappingSpans = mutableListOf<MatchedSpan>()
        var lastEnd = 0
        for (span in spans) {
            if (span.start >= lastEnd) {
                nonOverlappingSpans.add(span)
                lastEnd = span.end
            }
        }

        if (nonOverlappingSpans.isEmpty()) return text

        val spanToPlaceholder = nonOverlappingSpans.associateWith { span ->
            rawToPlaceholder.getOrPut(span.raw) {
                val normalizedKind = span.kind.trim().uppercase()
                    .filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' }
                    .ifEmpty { "MASK" }
                val index = kindCounters.merge(normalizedKind, 1, Int::plus)
                "{{${normalizedKind}_$index}}"
            }
        }

        val sb = StringBuilder(text.length)
        var cursor = 0
        for (span in nonOverlappingSpans) {
            sb.append(text, cursor, span.start)
            sb.append(spanToPlaceholder.getValue(span))
            cursor = span.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }
}
