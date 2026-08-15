package com.niki914.okia.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * SSE 行切分器：把任意分块的 UTF-8 字符串流切成行（Flow<SseLine>）。
 * 处理 \n / \r\n / \r 三种分隔符（含跨块 \r\n）、EOF 无换行 flush、
 * 流首 BOM 移除。行分类：注释行（: 开头）→ SseLine(null)，空行 →
 * SseLine("")，其他 → SseLine(原文)。null / 空行是 idle 检测的到达
 * 证据（§5.8），保留不丢弃。
 * 纯逻辑、协议无关：T8 默认 HttpEngine 与测试用它把 body 切成行。
 * Design source: W3C HTML spec Server-Sent Events（行解析）；codex
 * eventsource_stream（Rust 标准库，同一语义）。
 */
class SseLineParser {

    /**
     * 切分行流。状态在 flow 构建器内创建，冷流：每次 collect 独立
     * 状态，不跨 collect 泄漏。上游异常 / 取消直接传播。
     */
    fun parse(input: Flow<String>): Flow<SseLine> = flow {
        val buffer = StringBuilder()
        var atStreamStart = true
        var pendingCR = false

        suspend fun emitLine(text: String) {
            emit(
                when {
                    text.startsWith(":") -> SseLine(null)   // 注释 / keep-alive
                    text.isEmpty() -> SseLine("")           // 空行 = 事件边界
                    else -> SseLine(text)
                }
            )
        }

        input.collect { rawChunk ->
            var chunk = rawChunk
            if (atStreamStart) {
                atStreamStart = false
                // 流首 BOM 移除；块中间出现的 U+FEFF 是普通字符
                if (chunk.startsWith("\uFEFF")) chunk = chunk.substring(1)
            }
            var i = 0
            while (i < chunk.length) {
                when (val c = chunk[i]) {
                    '\n' -> {
                        // 跨块 \r\n 的尾巴（\r 已触发行结束），不产生新行
                        if (pendingCR) pendingCR = false
                        else { emitLine(buffer.toString()); buffer.clear() }
                    }
                    '\r' -> {
                        emitLine(buffer.toString())
                        buffer.clear()
                        if (i + 1 < chunk.length && chunk[i + 1] == '\n') {
                            i++  // \r\n 视为一个分隔符，跳过 \n
                        } else if (i + 1 == chunk.length) {
                            pendingCR = true  // 跨块：\r 结尾，等下一块开头
                        }
                    }
                    else -> {
                        pendingCR = false  // 非 \n 字符：\r 已单独作分隔符处理
                        buffer.append(c)
                    }
                }
                i++
            }
        }
        // EOF flush：末尾无换行的最后一行
        if (buffer.isNotEmpty()) emitLine(buffer.toString())
    }
}
