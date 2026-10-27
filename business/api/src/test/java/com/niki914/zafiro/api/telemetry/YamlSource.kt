package com.niki914.zafiro.api.telemetry

internal sealed interface YVal {
    data class Scalar(val value: Any) : YVal
    data class Seq(val items: List<YVal>) : YVal
    data class Mapping(val entries: List<Pair<String, YVal>>) : YVal
}

internal fun parseYamlMap(source: String): List<Pair<String, YVal>> {
    val parser = YamlParser(source)
    val entries = parser.parseMap(0)
    parser.end()
    return entries
}

private class YamlParser(source: String) {
    private data class Line(val indent: Int, val text: String, val number: Int)

    private val lines: List<Line>
    private var index = 0

    init {
        lines = source.split('\n').mapIndexedNotNull { offset, raw ->
            val text = raw.trimEnd('\r').trimEnd()
            val indent = text.indexOfFirst { it != ' ' }.let { if (it < 0) text.length else it }
            val body = text.substring(indent)
            if (body.isEmpty() || body.startsWith("#")) return@mapIndexedNotNull null
            if (text.take(indent).any { it != ' ' }) error("line ${offset + 1}: indent must be spaces")
            Line(indent, body, offset + 1)
        }
    }

    fun end() {
        if (index != lines.size) error("line ${lines[index].number}: trailing content")
    }

    fun parseMap(indent: Int): List<Pair<String, YVal>> {
        val entries = mutableListOf<Pair<String, YVal>>()
        while (index < lines.size && lines[index].indent == indent && !lines[index].text.startsWith("- ")) {
            val line = lines[index]
            val (key, rest) = splitKey(line.text, line.number)
            if (entries.any { it.first == key }) error("line ${line.number}: duplicate key $key")
            index += 1
            val value = if (rest.isEmpty()) parseNested(indent, line.number) else parseFlow(rest, line.number)
            entries += key to value
        }
        if (entries.isEmpty()) error("expected a map at indent $indent")
        return entries
    }

    private fun parseSeq(indent: Int): YVal.Seq {
        val items = mutableListOf<YVal>()
        while (index < lines.size && lines[index].indent == indent && lines[index].text.startsWith("- ")) {
            val line = lines[index]
            val rest = line.text.removePrefix("- ").trim()
            index += 1
            if (rest.isEmpty()) {
                items += parseNested(indent, line.number)
                continue
            }
            if (looksLikeKey(rest)) {
                val (key, valueRest) = splitKey(rest, line.number)
                val first = if (valueRest.isEmpty()) parseNested(indent, line.number) else parseFlow(valueRest, line.number)
                val more = if (
                    index < lines.size &&
                    lines[index].indent > indent &&
                    !lines[index].text.startsWith("- ")
                ) {
                    parseMap(lines[index].indent)
                } else {
                    emptyList()
                }
                if (more.any { it.first == key }) error("line ${line.number}: duplicate key $key")
                items += YVal.Mapping(listOf(key to first) + more)
            } else {
                items += parseFlow(rest, line.number)
            }
        }
        if (items.isEmpty()) error("expected a list at indent $indent")
        return YVal.Seq(items)
    }

    private fun parseNested(parentIndent: Int, lineNumber: Int): YVal {
        if (index >= lines.size || lines[index].indent <= parentIndent) {
            error("line $lineNumber: expected a nested value")
        }
        val child = lines[index]
        return if (child.text.startsWith("- ")) parseSeq(child.indent) else YVal.Mapping(parseMap(child.indent))
    }

    private fun parseFlow(raw: String, line: Int): YVal {
        val text = raw.trim()
        if (text.startsWith("[") && text.endsWith("]")) {
            val inner = text.substring(1, text.length - 1).trim()
            if (inner.isEmpty()) return YVal.Seq(emptyList())
            return YVal.Seq(splitCommas(inner, line).map { parseFlow(it, line) })
        }
        return YVal.Scalar(parseScalar(text, line))
    }
}

private fun looksLikeKey(text: String): Boolean {
    val colon = text.indexOf(':')
    if (colon <= 0) return false
    val key = text.substring(0, colon).trim()
    return key.isNotEmpty() && key.none { it.isWhitespace() }
}

private fun splitKey(text: String, line: Int): Pair<String, String> {
    val colon = text.indexOf(':')
    if (colon <= 0) error("line $line: expected a key")
    val key = text.substring(0, colon).trim()
    val rest = text.substring(colon + 1).trim()
    if (key.isEmpty() || key.any { it.isWhitespace() }) error("line $line: bad key")
    return key to rest
}

private fun splitCommas(text: String, line: Int): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    for (ch in text) {
        if (ch == '"') {
            quoted = !quoted
            current.append(ch)
        } else if (ch == ',' && !quoted) {
            parts += current.toString().trim()
            current.clear()
        } else {
            current.append(ch)
        }
    }
    if (quoted) error("line $line: unclosed quote")
    val tail = current.toString().trim()
    if (tail.isNotEmpty()) parts += tail
    return parts
}

private fun parseScalar(text: String, line: Int): Any {
    if (text == "true") return true
    if (text == "false") return false
    if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
        return text.substring(1, text.length - 1)
    }
    if (text.matches(Regex("-?\\d+"))) {
        val number = text.toLongOrNull() ?: error("line $line: bad number")
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) error("line $line: int out of range")
        return number.toInt()
    }
    if (text.any { it.isWhitespace() } || text.startsWith("{") || text.startsWith("[")) {
        error("line $line: unsupported value")
    }
    return text
}
