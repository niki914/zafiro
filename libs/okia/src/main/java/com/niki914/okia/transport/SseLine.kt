package com.niki914.okia.transport

/**
 * 协议解析前的一条原始 SSE 行。null data 标记注释或 keep-alive 行，
 * 解析器保留它作为 idle 证据而不是丢弃。
 * Design source: okia 骨架 SseLine（传输活动 idle 规则）。
 */
data class SseLine(val data: String?)
