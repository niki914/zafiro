package com.niki914.zafiro.api.text

/**
 * 会话列表、常驻通知、悬浮球正文预览里去掉的格式符。
 *
 * 只给模型正文用。搜索片段、工具运行文案、审批命令保持原字符，
 * 用户会拿星号当搜索词，命令里的星号也有含义。
 * 先调用本函数，再按各处自己的字数上限截断。
 */
object PreviewMarkers {
    private val MARKERS = Regex("""[*#`_~>]""")

    fun strip(text: String): String = text.replace(MARKERS, "")
}
