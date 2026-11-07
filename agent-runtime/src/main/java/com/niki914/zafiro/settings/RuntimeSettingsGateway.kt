package com.niki914.zafiro.settings

import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMaskingRule
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeRevolverPick
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeToolValidation

interface RuntimeSettingsGateway {
    suspend fun readLlmConfig(
        agentId: String = "main",
        configId: String? = null,
    ): RuntimeLlmConfig

    suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> = emptyList()

    suspend fun loadSkill(id: String): RuntimeLoadedSkill? = null

    suspend fun listMcpServers(): List<RuntimeMcpServer>

    suspend fun addMemory(value: String)

    suspend fun removeMemory(oldText: String): MemoryMutationResult

    suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult

    suspend fun listCustomPyTools(): List<RuntimeCustomPyTool>

    suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean = true,
    ): RuntimeToolValidation?

    suspend fun deleteCustomPyTool(name: String)

    suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean)

    suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting>

    suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation?

    // 组定义在 app 层，网关仅透传 groupId；校验由实现完成。
    suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation?

    suspend fun listExecutionRules(): List<RuntimeExecutionRule>

    suspend fun listMaskingRules(): List<RuntimeMaskingRule> = emptyList()
    /**
     * r22c：轮值候选选取（B4「delay 完全没生效」修复）。
     *
     * 把速率/轮值状态提升到网关持有，由 router hook 在每次 transport 发送尝试前调用，
     * 返回本次应发到的候选，使「同一模型连续请求」与「轮值换到不同模型」两种间隔都实际生效——
     * 区别于 [readLlmConfig] 只在一回合开头读一次、随后把会话钉死在某份配置上。
     *
     * r23：候选一律「立即发送」（[RuntimeRevolverPick.waitMs] 恒 0）；间隔改由发送后冷却
     * 门控下一次选取（见 [revolverMarkSent] / [revolverAnswerReceived] / [revolverLastRoutedConfigId]）。
     * 计时器门控的是「下一个请求」，从不等当前这次。
     *
     * 轮值关闭或候选不足（<2）时返回 null，调用方保持原请求不动（no-op）。
     * 带默认实现（返回 null）保持非破坏：未实现轮值的网关/测试桩行为不变。
     */
    suspend fun pickRevolverCandidate(
        agentId: String = "main",
        configId: String? = null,
    ): RuntimeRevolverPick? = null

    /**
     * r23：轮值「发送后」计数的启动（发送后冷却，非发送前等待）。
     *
     * router hook 在 `afterRequest`（请求刚打出去的时刻）调用：把该配置的解锁点登记为
     * `now + delaySeconds * 1000`（真实秒 → 毫秒），并标记其回答未决。下一次
     * [pickRevolverCandidate] 在该配置的解锁点过去、且回答已收到（[revolverAnswerReceived]）
     * 之前，都不会选中它。
     *
     * 默认 no-op（未实现轮值的网关/测试桩行为不变）。
     */
    suspend fun revolverMarkSent(configId: String, delaySeconds: Long) {}

    /**
     * r23：轮值「回答已收到」的登记（回合终态：成功 / 流错误 / 失败 / 无终态兜底）。
     *
     * 清除该配置的「回答未决」标记；冷却解锁点与标记同时满足后，下一轮
     * [pickRevolverCandidate] 才可再次选中它。默认 no-op（非破坏）。
     */
    suspend fun revolverAnswerReceived(configId: String) {}

    /**
     * r23：某配置的轮值冷却秒数（0 = 未设/无冷却）。供 router hook 在 `afterRequest` 取真实
     * 配置值传给 [revolverMarkSent]。默认 0 保持非破坏。
     */
    suspend fun revolverDelaySeconds(configId: String): Long = 0L

    /**
     * r23：最近一次 router hook 路由到的轮值配置 id（回合终态观察者据此登记回答已收到）。
     * 默认 null（非破坏）。
     */
    suspend fun revolverLastRoutedConfigId(): String? = null
}
