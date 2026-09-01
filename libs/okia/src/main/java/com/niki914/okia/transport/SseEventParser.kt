package com.niki914.okia.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * SSE 事件聚合器：把行流（Flow<SseLine>）聚合为结构化事件
 * （Flow<SseEvent>）。空行 = 事件边界（dispatch）；data 字段多行
 * 用 \n 拼接；注释行（null）跳过不产出（keep-alive 证据仍在行流
 * 中供 idle 检测）；流结束时 data 缓冲非空的事件照常 dispatch
 * （EOF flush）。严格 W3C 标准：data 缓冲为空字符串的事件丢弃。
 * event 字段透出（MCP 用 event 过滤），id / retry 忽略。
 * Design source: W3C HTML spec Server-Sent Events；codex
 * eventsource_stream / sse_stream crate（同一语义）。
 */
class SseEventParser {

    /**
     * 聚合事件流。状态在 flow 构建器内创建，冷流：每次 collect 独立
     * 状态。上游异常 / 取消直接传播。
     */
    fun parse(lines: Flow<SseLine>): Flow<SseEvent> = flow {
        val dataBuffer = StringBuilder()
        var eventType: String? = null

        suspend fun dispatch() {
            if (dataBuffer.isNotEmpty()) {
                emit(SseEvent(data = dataBuffer.toString(), event = eventType))
            }
            dataBuffer.clear()
            eventType = null
        }

        lines.collect { line ->
            when {
                line.data == null -> Unit                        // 注释行 / keep-alive
                line.data.isEmpty() -> dispatch()                // 空行 = 事件边界
                else -> parseField(line.data, dataBuffer) { eventType = it }
            }
        }
        dispatch()  // EOF flush：无尾空行的挂起事件
    }

    // 单行字段解析：第一个冒号分隔字段名 / 值；值以一个前导空格开头时移除该空格
    private fun parseField(
        line: String,
        dataBuffer: StringBuilder,
        setEventType: (String?) -> Unit
    ) {
        val colon = line.indexOf(':')
        if (colon < 0) return  // 无冒号：字段名为空，忽略该行
        val name = line.substring(0, colon)
        val value = when {
            colon + 1 >= line.length -> ""
            line[colon + 1] == ' ' -> line.substring(colon + 2)
            else -> line.substring(colon + 1)
        }
        when (name) {
            "data" -> {
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(value)
            }

            "event" -> setEventType(value)
            else -> Unit  // id / retry / 未知字段忽略
        }
    }
}
