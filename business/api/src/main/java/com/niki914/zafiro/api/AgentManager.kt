package com.niki914.zafiro.api

/**
 * Agent 多实例管理中枢。
 *
 * 统一管理主前台长驻对话与瞬态任务对话：
 * - [main]：主前台对话，绑定主 UI 输入框与聊天界面，会话历史持久化入 Room；
 * - [createTaskAgent]：按需创建瞬态 Task Agent，供宿主语音助手、后台快捷动作使用，
 *   纯内存运行，不写 Room 历史，生命周期由调用方持有；
 * - [control]：先到先得的全局聚合控制面，供系统常驻通知、悬浮窗、屏幕常亮统一观察。
 */
interface AgentManager {
    /** 主前台对话（长驻且唯一与主 UI 绑定的会话）。 */
    val main: Agent

    /**
     * 创建一个独立的瞬态任务 Agent。
     * 调用方按需持有；未被持有时随协程或 GC 释放。
     */
    fun createTaskAgent(tag: String = "task"): Agent

    /** 全局聚合状态控制面（先到先得仲裁）。 */
    val control: AgentControl
}
