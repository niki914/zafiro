package com.niki914.zafiro.business.remote.profile

/** BCP 47。空串或超过 [maxLength] 个字符时没有可上报的语言。 */
internal fun boundedLanguageTag(tag: String, maxLength: Int): String? {
    if (tag.isEmpty() || tag.length > maxLength) return null
    return tag
}
