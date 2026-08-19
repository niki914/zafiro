package com.niki914.okia.tooling

/**
 * 工具线缆名（provider 可见工具名）的派生与消歧。
 *
 * 背景：MCP 工具名是用户可控字符串，可含 `.`、空格等；OpenAI / Anthropic /
 * Gemini 对 function name 的约束为 `^[a-zA-Z0-9_-]{1,64}$` 量级。注册名原样
 * 上线缆会因非法字符或超长被 Provider 拒绝，而 MCP 调用又需要还原原始工具名。
 * 因此线缆名与原始名（ToolDescriptor.name）分离：原始名只用于 MCP 调用，线缆
 * 名只用于 Provider 请求体与 registry 键。
 *
 * 规则（D1 B / D2 B / D4 A）：
 * - 段规范化（sanitizeSegment）：保留 ASCII 字母数字与 `-`、`_`，其余替换为
 *   `_`；空段兜底 `_`。
 * - 本地工具线缆名 = sanitize(name)。
 * - MCP 工具线缆名 = `mcp__<server>__<tool>`（两端段各自规范化 + 分段截断，
 *   保证总长 ≤ 64）。
 * - 长度预算与哈希消歧对齐 codex tools.rs：超长优先保前缀、尾截断；sanitize
 *   后碰撞（如 a.b 与 a_b）由 disambiguate 追加 FNV-1a 哈希后缀消歧，attempt
 *   迭代保证最终唯一。
 * Design source: codex codex-rs/codex-mcp/src/tools.rs（normalize_tools_for_
 * model_with_prefix / fit_callable_parts_with_hash / unique_callable_parts）、
 * mcp/mod.rs sanitize_responses_api_tool_name。
 */
object ToolWireName {

    /** Provider function name 长度上限（OpenAI / Anthropic 均 64）。 */
    const val MAX_LENGTH = 64

    private const val MCP_PREFIX = "mcp__"
    private const val SEP = "__"
    private const val HASH_HEX_LENGTH = 12
    private const val HASH_SUFFIX_LENGTH = HASH_HEX_LENGTH + 1 // "_" + 12 hex
    private const val MAX_SERVER_SEGMENT = 32

    /** 本地工具的线缆名：段规范化 + 长度截断。 */
    fun forLocal(name: String): String = truncate(sanitizeSegment(name), MAX_LENGTH)

    /** MCP 工具线缆名：mcp__<server>__<tool>，分段截断保证 ≤ MAX_LENGTH。 */
    fun forMcp(serverName: String, toolName: String): String {
        val server = truncate(sanitizeSegment(serverName), MAX_SERVER_SEGMENT)
        val toolBudget = MAX_LENGTH - MCP_PREFIX.length - SEP.length - server.length
        val tool = truncate(sanitizeSegment(toolName), toolBudget)
        return MCP_PREFIX + server + SEP + tool
    }

    /**
     * 在 used 集合内对 base 做唯一性消歧。
     * base 未占用且未超长 → 原样返回；否则追加 `_` + 哈希后缀（必要时尾部
     * 截断到 MAX_LENGTH），attempt 迭代（哈希输入掺入 attempt）保证最终返回
     * 一个不在 used 中的名字。rawIdentity 是工具的唯一确定性标识（如
     * `server\u0000tool`），保证同工具重复派生输出稳定。
     */
    fun disambiguate(base: String, rawIdentity: String, used: Set<String>): String {
        if (base.length <= MAX_LENGTH && base !in used) return base
        var attempt = 0
        while (true) {
            val identity = if (attempt == 0) rawIdentity else "$rawIdentity\u0000$attempt"
            val suffix = "_" + fnvHex(identity).takeLast(HASH_HEX_LENGTH)
            val budget = MAX_LENGTH - HASH_SUFFIX_LENGTH
            val candidate = truncate(base, budget) + suffix
            if (candidate !in used) return candidate
            attempt++
        }
    }

    private fun sanitizeSegment(name: String): String {
        if (name.isEmpty()) return "_"
        return buildString(name.length) {
            for (c in name) {
                append(
                    if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') c
                    else '_'
                )
            }
        }
    }

    private fun truncate(value: String, maxLen: Int): String =
        if (value.length <= maxLen) value else value.take(maxLen)

    /** FNV-1a 64 位哈希（确定性；hex 小写）。用于消歧后缀，无需密码学强度。 */
    private fun fnvHex(input: String): String {
        var hash = -3750763034362895579L // FNV-1a offset basis
        for (b in input.encodeToByteArray()) {
            hash = hash xor (b.toLong() and 0xffL)
            hash = hash * 1099511628211L // FNV-1a prime
        }
        return java.lang.Long.toHexString(hash)
    }
}
