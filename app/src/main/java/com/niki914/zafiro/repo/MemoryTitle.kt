package com.niki914.zafiro.repo

/** A short, human title for a memory entry, so a list of memories can be scanned instead of read. Pure. */
object MemoryTitle {
    private const val MAX = 44

    fun of(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return "(empty)"
        // "Label: details" -> "Label" when the label is short ("Device", "OWNER RULE (2026-11-13)", "Job-hunt mission").
        val colon = t.indexOf(':')
        if (colon in 3..MAX) return tidy(t.substring(0, colon))
        val firstSentence = t.split(Regex("(?<=[.!?])\\s")).first()
        val base = if (firstSentence.length in 6..90) firstSentence else t
        return tidy(cut(base))
    }

    private fun cut(s: String): String {
        if (s.length <= MAX) return s
        val head = s.take(MAX)
        val at = head.lastIndexOf(' ').takeIf { it > MAX / 2 } ?: MAX
        return head.take(at).trimEnd(',', ';', ' ', '-', '(') + "…"
    }

    private fun tidy(s: String): String = s.trim().trimEnd('.', ':').replaceFirstChar { it.uppercase() }
}
