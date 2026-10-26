package com.niki914.zafiro.api.telemetry

/**
 * 用和服务端核对脚本相同的方式读 Kotlin 字面量：先去掉注释，再按行匹配声明。
 * 读不懂的行跳过。同名第二次不覆盖第一次。
 */
internal class RawLiteral(val raw: String)

internal data class ParsedKotlin(
    val consts: Map<String, Any>,
    val duplicates: List<String>,
)

internal fun stripKotlinComments(source: String): String {
    val out = StringBuilder()
    var i = 0
    fun at(index: Int): Char = if (index < source.length) source[index] else '\u0000'
    while (i < source.length) {
        val ch = source[i]
        val next = at(i + 1)
        if (ch == '"' && next == '"' && at(i + 2) == '"') {
            out.append("\"\"\"")
            i += 3
            while (i < source.length) {
                if (at(i) == '"' && at(i + 1) == '"' && at(i + 2) == '"') {
                    out.append("\"\"\"")
                    i += 3
                    break
                }
                out.append(source[i])
                i += 1
            }
            continue
        }
        if (ch == '"') {
            out.append(ch)
            i += 1
            while (i < source.length) {
                val s = source[i]
                if (s == '\\') {
                    out.append(s)
                    if (i + 1 < source.length) out.append(source[i + 1])
                    i += 2
                    continue
                }
                out.append(s)
                i += 1
                if (s == '"') break
            }
            continue
        }
        if (ch == '\'') {
            out.append(ch)
            i += 1
            while (i < source.length) {
                val s = source[i]
                if (s == '\\') {
                    out.append(s)
                    if (i + 1 < source.length) out.append(source[i + 1])
                    i += 2
                    continue
                }
                out.append(s)
                i += 1
                if (s == '\'') break
            }
            continue
        }
        if (ch == '/' && next == '/') {
            i += 2
            while (i < source.length && source[i] != '\n') i += 1
            continue
        }
        if (ch == '/' && next == '*') {
            i += 2
            while (i < source.length && !(source[i] == '*' && at(i + 1) == '/')) {
                if (source[i] == '\n') out.append('\n')
                i += 1
            }
            i += 2
            continue
        }
        out.append(ch)
        i += 1
    }
    return out.toString()
}

internal fun parseLiteral(raw: String): Any {
    val text = raw.trim().removeSuffix(",").trim()
    if (text == "true") return true
    if (text == "false") return false
    if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
        return text.substring(1, text.length - 1)
    }
    val withoutLong = if (text.endsWith("L") || text.endsWith("l")) text.dropLast(1) else text
    val normalized = withoutLong.replace("_", "")
    if (normalized.matches(Regex("-?\\d+"))) {
        val number = normalized.toLong()
        if (number in Int.MIN_VALUE..Int.MAX_VALUE) return number.toInt()
        return RawLiteral(text)
    }
    return RawLiteral(text)
}

private val declaration = Regex(
    """^(?:public|internal|private)?\s*const\s+val\s+([A-Za-z_][A-Za-z0-9_]*)\b(.*)$""",
)

internal fun parseKotlin(source: String): ParsedKotlin {
    val consts = linkedMapOf<String, Any>()
    val duplicates = mutableListOf<String>()
    for (rawLine in stripKotlinComments(source).split(Regex("\\r?\\n"))) {
        val match = declaration.matchEntire(rawLine.trim()) ?: continue
        val name = match.groupValues[1]
        val rest = match.groupValues[2]
        val eq = rest.indexOf('=')
        if (eq < 0) continue
        if (consts.containsKey(name)) {
            duplicates += name
            continue
        }
        consts[name] = parseLiteral(rest.substring(eq + 1))
    }
    return ParsedKotlin(consts, duplicates)
}

private val watchedPrefixes = listOf("LIMIT_", "CHANNEL_", "CONFIG_KEY_")

internal fun compareKotlin(source: String, mapping: List<MappedConst>): List<String> {
    val parsed = parseKotlin(source)
    val diffs = mutableListOf<String>()
    for (entry in mapping) {
        val actual = parsed.consts[entry.kotlin]
        val same = actual != null && actual !is RawLiteral && actual == entry.value
        if (!same) {
            diffs += "${entry.kotlin}: server=${formatLiteral(entry.value)} android=${formatLiteral(actual)}"
        }
    }
    for (name in parsed.duplicates) {
        diffs += "$name: server=one definition android=duplicate"
    }
    val known = mapping.map { it.kotlin }.toSet()
    for ((name, value) in parsed.consts) {
        val watched = watchedPrefixes.any { name.startsWith(it) }
        if (watched && name !in known) {
            diffs += "$name: server=not in mapping android=${formatLiteral(value)}"
        }
    }
    return diffs
}

internal fun formatLiteral(value: Any?): String = when (value) {
    null -> "missing"
    is String -> "\"$value\""
    is RawLiteral -> value.raw
    else -> value.toString()
}
