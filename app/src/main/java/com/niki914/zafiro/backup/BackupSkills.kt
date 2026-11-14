package com.niki914.zafiro.backup

/**
 * 改过的内置技能必须带上 `.seed`，且标记不能等于当前正文。
 * 否则下次启动会把目录当成未修改并覆盖。
 */
internal fun packSkillFiles(skill: SkillSnapshot): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>(skill.files.size + 1)
    skill.files.forEach { (name, bytes) ->
        if (safeRelative(name)) files[name] = bytes
    }
    val current = files[SKILL_FILE] ?: return files
    val bundled = skill.bundledSkillMd ?: return files
    if (current.contentEquals(bundled)) return files
    val contentHash = sha256Hex(current)
    val marker = files[SEED_FILE]?.toString(Charsets.UTF_8)?.trim()
    if (marker == null || marker == contentHash) {
        files[SEED_FILE] = sha256Hex(bundled).toByteArray(Charsets.UTF_8)
    }
    return files
}

internal fun safeRelative(path: String): Boolean {
    if (path.isEmpty() || path.startsWith("/")) return false
    val segments = path.split('/')
    return segments.none { it.isEmpty() || it == "." || it == ".." }
}

internal fun safeSkillId(id: String): Boolean {
    return safeRelative(id) && id.split('/').size in 1..2
}

private const val SKILL_FILE = "SKILL.md"
private const val SEED_FILE = ".seed"
