package com.niki914.zafiro.api

import com.niki914.zafiro.api.model.AgentState
import kotlinx.coroutines.flow.StateFlow

/**
 * Agent 多实例管理中枢。
 *
 * 统一管理主前台长驻对话与瞬态任务对话：
 * - [main]：主前台对话，绑定主 UI 输入框与聊天界面，会话历史持久化入 Room；
 * - [mainFlow]：主前台会话响应式流，发生切换时向下游发射最新绑定的 [Agent]；
 * - [switchTo]：平滑切换或恢复指定会话（复用后台存活实例或从存储重建）；
 * - [startNewConversation]：开启新的未持久化空白草稿会话；
 * - [activeStates]：活跃容器中的全部会话状态表（供列表页响应式展示 Loading 与铃铛）；
 * - [createTaskAgent]：按需创建瞬态 Task Agent，供宿主语音助手、后台快捷动作使用；
 * - [control]：全局聚合控制面（始终跟随当前 [main]）。
 */
interface AgentManager {
    /** 主前台对话（长驻且唯一与主 UI 绑定的会话）。 */
    val main: Agent

    /** 主前台对话响应式流（前台切换时发射新绑定的 Agent）。 */
    val mainFlow: StateFlow<Agent>

    /**
     * 切换或载入指定会话为主前台。
     * 若容器内已有该会话的活跃 Agent，则直接复用；否则从存储建档载入。
     */
    suspend fun switchTo(conversationId: String): Agent

    /**
     * 开启一个未持久化的空白草稿会话为主前台。
     * 前一个会话如果正在后台运行，继续执行，不被打断。
     */
    fun startNewConversation(): Agent

    /**
     * 内存中所有处于活跃容器中的会话状态映射（供列表页与外部响应式感知）。
     */
    val activeStates: StateFlow<Map<String, AgentState>>

    /**
     * 创建一个独立的瞬态任务 Agent。
     * - [isPersistent] 为 true 时：写入本地存储与活跃容器，支持持久化历史与后台状态感知；
     * - [isPersistent] 为 false 时：纯内存运行。
     * 调用方按需持有；未被持有时随协程或 GC 释放。
     */
    fun createTaskAgent(isPersistent: Boolean = false): Agent

    /**
     * 显式销毁并释放指定会话的 Agent 实例。
     * 若该会话处于活跃容器中，将立即中断其执行（stop）、安全释放外部资源（release）并移出内存映射。
     */
    fun destroy(conversationId: String)

    /** 全局聚合状态控制面（始终跟随当前 [main]）。 */
    val control: AgentControl
}
