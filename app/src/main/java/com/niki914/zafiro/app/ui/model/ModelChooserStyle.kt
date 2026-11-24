package com.niki914.zafiro.app.ui.model

/**
 * 首页模型选择器风格。
 *
 * [Detailed] 底部抽屉 OptionSheet，支持配置/思考强度/模型搜索；
 * [Compact] 顶栏下拉气泡菜单，快速切换已保存配置。
 */
enum class ModelChooserStyle(val storageKey: String) {
    Detailed("detailed"),
    Compact("compact");

    companion object {
        fun fromStorageKey(value: String?): ModelChooserStyle =
            entries.firstOrNull { it.storageKey == value } ?: Detailed
    }
}
