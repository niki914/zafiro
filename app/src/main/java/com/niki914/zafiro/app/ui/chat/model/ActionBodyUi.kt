package com.niki914.zafiro.app.ui.chat.model

import com.niki914.zafiro.app.ui.model.home.HomeChatImage

/**
 * 动作内容纯分流模型：
 * 绝不在 Compose 树中做 JSON 解析或多层 if-else，所有决策前置在此模型中表达。
 */
sealed interface ActionBodyUi {

    /** 1. 定制样式（Customized Style）：自身全权负责布局，不接受外部传入复制按钮 */
    sealed interface Custom : ActionBodyUi {
        /** 命令行/Python/脚本执行：带命令输入预览、复制输入按钮与控制台输出面板 */
        data class Command(
            val command: String,
            val copyText: String?,
            val output: String,
            val isError: Boolean,
            val isRunning: Boolean = false,
        ) : Custom

        /** 图片返回：图片缩略卡片网格 */
        data class ImagePreview(
            val images: List<HomeChatImage>,
        ) : Custom

        /** 技能加载（load_skill）：有意义的阅读 Markdown 内容，不提供复制按钮 */
        data class SkillContent(
            val markdown: String,
        ) : Custom

        /** 思考块（Thinking）：有意义的阅读文本，不提供复制按钮，支持自动滚到底跟随 */
        data class Thinking(
            val text: String,
            val isComplete: Boolean,
            val autoScrollToEnd: Boolean = false,
        ) : Custom

        /** 错误提示卡片（Error / Failure）：展示错误信息 */
        data class ErrorDetail(
            val title: String,
            val body: String?,
            val isMonospace: Boolean,
            val showCopyButton: Boolean,
        ) : Custom

        /** 传输重试进行中（Retrying） */
        data class Retrying(
            val attempt: Int,
            val maxAttempts: Int,
            val reason: String,
        ) : Custom
    }

    /** 2. 通用工具样式（Standard Style）：严格受详细程度开关与实质内容规则驱动 */
    sealed interface Standard : ActionBodyUi {
        /** 简单模式（alwaysShowDetailed = false）：仅显示单行成功/失败/运行中状态，无复制按钮 */
        data class Simple(
            val isFailed: Boolean = false,
            val isRunning: Boolean = false,
            val reason: String? = null,
        ) : Standard

        /** 详细模式（alwaysShowDetailed = true）：面板底色 + 等宽结果文本 */
        data class Detailed(
            val text: String,
            val isError: Boolean,
            /** 当输出有实质意义时为 true，显示复制按钮；否则为 false */
            val showCopyButton: Boolean,
        ) : Standard
    }
}
