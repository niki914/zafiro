package com.niki914.zafiro.repo

import com.niki914.logging.Logger
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.niki914.zafiro.settings.model.DEFAULT_MAX_TOKENS
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
    // r27 F15：测试可注入的「流水线阶段等待上限」。生产默认 REVOLVER_MAX_WAIT_MS（10s）；
    // 测试注入 100ms 把超时-置空终态做到确定性（F4 回归测试：见 XRepoRuntimeGatewayTest）。
    // 默认参数保留所有现有调用点（AppRuntimeBridge / 测试桩）可编译。
    maxWaitMs: Long = REVOLVER_MAX_WAIT_MS,
) : RuntimeSettingsGateway {
    // r27：maxWaitMs 是构造参数值，存到实例字段供 pickRevolverCandidate 使用
    // （构造器参数本身作用域仅限初始化；pick 在构造完之后调用，需要存到字段）。
    private val revolverMaxWaitMs: Long = maxWaitMs

    /**
     * 轮值游标（进程内）：单一事实源。连续无绑定读按周期槽位逐个轮转，
     * 重启复位（无需持久化——游标值本身不影响轮转对称性）。
     * 与 r25 的 `revolverIndex` 完全一致，r27 未改名（F12 / F13 都保留这个字段）。
     */
    @Volatile
    private var revolverIndex = 0

    /**
     * r27 F11 流水线门控四元组（取代 r25 的 pipelineStageUntilMs / stageActive /
     * pipelineStageAnswerReceived / pipelineStageOwner + revolverCooldownUntil +
     * revolverAnswerPending 六个字段）。
     *
     * 语义：每次请求「发出后」武装一个 stage（属主 = 本次发到的键，解锁点 = now + delay*1000，
     * delay=0 则解锁点=now 即「延迟侧立即满足」），stage 保持挂起直到「到点 AND 属主回答已收」
     * 两者同时满足时由 [pickRevolverCandidate] 收割放行。整个轮值序列因此是一个长临界区，
     * 总吞吐由各键延迟之和封顶（用户 2026-11-04 规格）——而不是各键并行冷却。
     *
     * - [stageArmed]：当前有无挂起的 stage。由 [revolverMarkSent] 原子置 true；
     *   [pickRevolverCandidate] 在「到点 AND 属主回答」同时满足时置 false 并放行。
     * - [stageGateUntilMs]：stage 延迟地板毫秒时间戳。delay=0 → now（延迟侧立即满足），
     *   delay>0 → now + delay*1000。stage 收割时重置为 0。
     * - [stageAnswerReceived]：stage 属主的回答是否已收到。[revolverAnswerReceived] 仅当
     *   key == [stageOwnerKey] 时置 true（属主专属，乱序 / 旧键的迟到回答不影响本 stage）；
     *   [revolverMarkSent] 置 false（新 stage 开始，回答未收）。
     * - [stageOwnerKey]：当前 stage 属主 configId。
     *
     * 全部在 [revolverLock] 锁内读写（@Volatile 仅为跨线程可见性基线；实际写入都在锁内）。
     * 进程内、重启复位（速率限制冷却本就不跨进程有意义）。
     */
    @Volatile
    private var stageArmed = false

    @Volatile
    private var stageGateUntilMs = 0L

    @Volatile
    private var stageAnswerReceived = false

    @Volatile
    private var stageOwnerKey: String? = null

    /**
     * 最近一次 router hook 路由到的轮值配置 id（即 [revolverMarkSent] 登记的属主）。
     * 回合终态观察者 [LlmStreamPipeline.execute().finally] 据此调用
     * [revolverAnswerReceived] 做幂等的安全网（F17）。volatile：beforeRequest（httpEngine
     * 派发线程）写、afterRequestStream / 回合终态读。
     */
    @Volatile
    private var lastRoutedConfigId: String? = null

    /** 流水线阶段是进程内可变状态，跨并发请求（多 agent / 压缩 + 聊天同时）加锁。 */
    private val revolverLock = Mutex()

    private companion object {
        const val LOG_TAG = "niki914_zafiro_XRepoRuntimeGateway"
        const val SOURCE_USER = "user"
        const val SOURCE_PROVIDER = "provider"
        const val SOURCE_OPENROUTER = "openrouter"
        const val SOURCE_DEFAULT = "default"
        /**
         * r27 F15：流水线阶段「到点但回答未收」的等待上限（构造器可覆盖）。
         * 超过后 pick 直接返回 null（跳过本次槽位、保持回合活跃），绝不无限阻塞——
         * 这是 F4 缺陷类（零-sleep 自旋）被构造器字段化的核心保证。
         */
        const val REVOLVER_MAX_WAIT_MS = 10_000L
    }

    override suspend fun readLlmConfig(agentId: String, configId: String?): RuntimeLlmConfig {
        val doc = repo.llmConfigs.document()
        val target = configId?.takeIf { it.isNotBlank() }?.let { id ->
            doc.configs.firstOrNull { it.id == id } ?: run {
                Logger.w(LOG_TAG, "Config id=$id not found, falling back to active config")
                null
            }
        }
        // 轮值（r21 F5 修复）：绑定会话也参与轮转——会话绑定的 configId 若仍存活的候选，
        // 按权重 + 冷却在候选集内挑一份；轮值关闭时绑定的 configId 保持 r19 语义（直接用绑定那份）。
        // 单候选或无候选回落 active，行为与 r19 完全一致（`readLlmConfig_withConfigId_usesTargetOrFallsBackToActive` 契约不变）。
        val active = target ?: revolverPick(doc, target?.id) ?: doc.activeConfig()
        if (target == null && doc.revolverEnabled && doc.revolverConfigs().size >= 2) {
            Logger.d(LOG_TAG, "revolver pick=${active?.id} model=${active?.model}")
        } else if (target != null && doc.revolverEnabled) {
            Logger.d(LOG_TAG, "bound target=${target.id} revolver still ON → active=${active?.id}")
        }
        val memories = repo.agents.memoriesFor(agentId)

        // 四级解析链（全查本地，零网络）：用户 > provider /models > OpenRouter > 兜底。
        // 逐级按需：用户填了就完全不碰两个缓存 store，缺哪层才读哪层。
        suspend fun providerFacts(): CloudModelFacts? {
            return repo.cloudFacts.providerFacts(active?.endpoint, active?.model)
        }

        suspend fun openRouterFacts(): CloudModelFacts? {
            return OpenRouterCatalogApi.match(
                repo.cloudFacts.openRouter().models,
                active?.model.orEmpty()
            )
        }

        val maxTokensLimit = resolveTokenCount(
            userText = active?.maxTokensText,
            providerValue = { providerFacts()?.maxOutputTokens },
            openRouterValue = { openRouterFacts()?.maxOutputTokens },
            fallback = DEFAULT_MAX_TOKENS.toLong(),
        )
        val contextWindowLimit = resolveTokenCount(
            userText = active?.contextWindowText,
            providerValue = { providerFacts()?.windowTokens },
            openRouterValue = { openRouterFacts()?.windowTokens },
            fallback = DEFAULT_CONTEXT_WINDOW_TOKENS,
        )
        val maxTokens = maxTokensLimit.first.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // 取值来源必须打进日志：UI 不展示来源，而云端值常常与默认值接近（1M vs 1048576），
        // 只看数字无法判断哪一层赢。
        Logger.i(
            LOG_TAG,
            "token limits model=${active?.model} " +
                    "maxTokens=$maxTokens(${maxTokensLimit.second}) " +
                    "contextWindow=${contextWindowLimit.first}(${contextWindowLimit.second})"
        )
        // 3d 归因：本次请求实际路由到的配置 label（「模型名 · 设置里的配置名」），
        // 轮值开启时每次请求都是当前选定那份，UI 错误卡片据此定位「是哪个模型出了问题」。
        val revolverLabel = active?.let { c ->
            val name = c.name.trim()
            if (name.isNotEmpty() && name != c.model) "${c.model} · $name" else c.model
        }.orEmpty()
        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = active?.endpoint.orEmpty(),
            apiKey = active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            protocol = active?.protocol.orEmpty(),
            supportsImages = active?.supportsImages ?: false,
            proxy = active?.proxy.orEmpty(),
            thinkingLevel = active?.thinkingLevel.orEmpty(),
            prompt = active?.prompt?.trim()?.takeIf(String::isNotBlank)
                ?: doc.prompt.trim().takeIf(String::isNotBlank)
                ?: "",
            memories = memories,
            idleTimeoutSeconds = repo.llmIdleTimeoutSeconds().takeIf { it > 0L },
            retryMaxAttempts = repo.llmRetryMaxAttempts(),
            repetitionSensitivity = repo.repetitionSensitivity(),
            compactionThresholdPercent = repo.contextCompactionThresholdPercent(),
            // 空串/旧格式的 0 = 未设置：用户 > 云端 > 默认
            maxTokens = maxTokens,
            contextWindowTokens = contextWindowLimit.first,
            revolverLabel = revolverLabel,
        )
    }

    /**
     * 轮值选取（r21 + r27 F12 重写）：`readLlmConfig` 在「一回合开始」时调用，
     * 把本回合的请求钉到周期里下一个槽位上。
     *
     * r27 语义：流水线阶段门控（[pickRevolverCandidate]）才是速率限制的唯一门；
     * 本方法只做「选哪个槽」的即时决策——立即按权重展开周期（`revolverCycle`）的
     * 当前游标位置返回该槽，并推进游标，无等待（规格第 1 点：「首请求无延迟发出」）。
     * 周期权重展开（N 个连续槽位 = N 个请求配额）通过 `revolverCycle()` 保留。
     *
     * 轮值关闭、周期为空、或不足 2 个候选 → 返回 null（调用方回落 active，r19 语义不变）。
     */
    private suspend fun revolverPick(
        doc: LlmConfigsDocument,
        targetId: String?,
    ): SavedLlmConfig? {
        if (!doc.revolverEnabled) return null
        val cycle = doc.revolverCycle()
        if (cycle.size < 2) return null
        // 绑定会话（targetId 非空）从被绑定的候选起步；无绑定继续游标轮转。
        // 推进游标后，下一次 [pickRevolverCandidate] 或 [revolverPick] 从这里继续。
        val startIndex = when {
            targetId != null -> cycle.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
            else -> revolverIndex
        }
        val n = cycle.size
        revolverLock.withLock {
            revolverIndex = (startIndex + 1) % n
        }
        return cycle[startIndex]
    }

    /**
     * r27 F11 顺序流水线门控：供 router hook 每次发送尝试调用，取「本次该发到的候选」。
     * 轮值关闭或候选不足（<2）返回 null（调用方保持原请求 no-op）。
     *
     * 间隔不再是「每模型独立冷却」，而是【顺序门控整条请求流水线】。一次请求只有在
     * (a) 上一次请求的回答已收到（[stageAnswerReceived]）AND (b) now ≥ [stageGateUntilMs]
     * （上一发送方的延迟已走过）之后才允许发出。整条序列因此是一个长临界区，
     * 总吞吐由各键延迟之和封顶（用户 2026-11-04 规格）——不是各键并行。
     *
     * **F4 缺陷类结构性消除**（r27 F18 build gate）：pick 循环只有一个 while(true)，
     * 两个终态出口——
     *   1. 收割（reap）：到点 AND 属主回答已收 → 在同一锁体内容直接 dispatch + 推进游标，
     *      循环 break；
     *   2. 超时（timeout）：now ≥ deadline（deadline = gateEnd + [revolverMaxWaitMs]）
     *      → 本次尝试返回 null，跳过槽位、保持回合活跃，游标也推进（下一次 pick 从下一槽位）。
     * 每个非终态迭代 sleep 严格正值（F11 证明）——不存在零-sleep 自旋，
     * 因此取消（聊天界面的 stop 按钮）永远在第一个 [delay] 处被观察到。
     *
     * 返回的 [RuntimeRevolverPick] 纯进程内瞬时值（apiKey 不落盘）；其等待已在方法内完成，
     * 调用方拿到即 dispatch（`waitMs` 恒为 0，仅作契约保留）。
     */
    override suspend fun pickRevolverCandidate(
        agentId: String,
        configId: String?,
    ): RuntimeRevolverPick? {
        val doc = repo.llmConfigs.document()
        if (!doc.revolverEnabled) return null
        val cycle = doc.revolverCycle()
        if (cycle.size < 2) return null
        val n = cycle.size

        // F13：configId 仅作为「扫描起点」提示；流水线阶段门控对所有键一视同仁
        // （绑定会话不绕过 stage，只重新锚定哪个槽先开火）。
        val startScanAt = when {
            configId != null -> cycle.indexOfFirst { it.id == configId }.coerceAtLeast(0)
            else -> revolverIndex
        }
        val nextSlot = cycle[startScanAt % n]

        // F12：无挂起 stage（= 流水线空闲 / 上一 stage 已被收割）→ 立即 dispatch，无等待。
        // 这是规格第 1 点「首请求无延迟发出」的实现。
        if (!revolverLock.withLock { stageArmed }) {
            return dispatchSlot(nextSlot, startScanAt, n)
        }

        // 有挂起 stage：进入收割-或-超时 双终态循环。
        // 锁内读 [stageGateUntilMs] 一次以固定 deadline（锁外 sleep 期间 stage 可能被新
        // markSent 重新武装，但 deadline 以本次进入时的 gateEnd 为准——避免无限推迟）。
        val gateEnd = revolverLock.withLock {
            val t0 = System.currentTimeMillis()
            maxOf(stageGateUntilMs, t0)
        }
        val deadline = gateEnd + revolverMaxWaitMs

        while (true) {
            val terminal = revolverLock.withLock {
                val now = System.currentTimeMillis()
                when {
                    // 终态 1：收割 → 复位 stage + dispatch + 推进游标，随后外层 break。
                    now >= stageGateUntilMs && stageAnswerReceived -> {
                        stageArmed = false
                        stageAnswerReceived = false
                        stageGateUntilMs = 0L
                        stageOwnerKey = null
                        dispatchSlotLocked(nextSlot, startScanAt, n)
                        "REAP" to 0L
                    }
                    // 终态 2：超时 → 跳过本次槽位（保持回合活跃），游标推进，break。
                    now >= deadline -> {
                        // 不重置 stage（仍挂起，下一次 pick 重新武装循环）；只推进游标。
                        revolverIndex = (startScanAt + 1) % n
                        "TIMEOUT" to 0L
                    }
                    // 非终态：必须 sleep 严格正值（F11 证明）。
                    else -> {
                        val sleepMs = minOf(stageGateUntilMs, deadline) - now
                        "SLEEP" to sleepMs
                    }
                }
            }
            when (terminal.first) {
                "REAP" -> {
                    return RuntimeRevolverPick(
                        configId = nextSlot.id,
                        endpoint = nextSlot.endpoint,
                        apiKey = nextSlot.apiKey,
                        model = nextSlot.model,
                        protocol = nextSlot.protocol,
                        label = slotLabel(nextSlot),
                        waitMs = 0L,
                    )
                }
                "TIMEOUT" -> {
                    Logger.w(
                        LOG_TAG,
                        "revolver pick TIMEOUT slot=${nextSlot.id} — skip, keep turn active " +
                                "(stage still armed, will re-arm on next attempt)"
                    )
                    return null
                }
                else -> {
                    val sleepMs = terminal.second
                    // F11 证明保证 sleepMs > 0（非终态时），但仍 coerceAtLeast 防御
                    // 时间回拨等极端情况；sleepMs 严格正值时是 suspension point。
                    if (sleepMs > 0L) delay(sleepMs)
                }
            }
        }
    }

    /**
     * F11 dispatch 锁内变体：在 [revolverLock] 锁已持有的上下文里 dispatch 一个槽位
     * （推进游标、登记 lastRoutedConfigId）。返回 null 表示调用方继续走外层循环；
     * 实际 pick 路径在 REAP 分支调用后随即 return，本方法只做副作用。
     */
    private fun dispatchSlotLocked(
        slot: SavedLlmConfig,
        startScanAt: Int,
        n: Int,
    ) {
        lastRoutedConfigId = slot.id
        revolverIndex = (startScanAt + 1) % n
    }

    /** 锁外 dispatch 辅助（无挂起 stage 时直接用）。锁内只做游标 + lastRouted 登记。 */
    private suspend fun dispatchSlot(
        slot: SavedLlmConfig,
        startScanAt: Int,
        n: Int,
    ): RuntimeRevolverPick {
        revolverLock.withLock {
            dispatchSlotLocked(slot, startScanAt, n)
        }
        Logger.d(
            LOG_TAG,
            "revolver pick immediate slot=${slot.id} model=${slot.model} " +
                    "protocol=${slot.protocol} (no stage armed)"
        )
        return RuntimeRevolverPick(
            configId = slot.id,
            endpoint = slot.endpoint,
            apiKey = slot.apiKey,
            model = slot.model,
            protocol = slot.protocol,
            label = slotLabel(slot),
            waitMs = 0L,
        )
    }

    /** 槽位 label（「模型名 · 配置名」，轮值关闭 / 单候选路径复用）。 */
    private fun slotLabel(slot: SavedLlmConfig): String {
        val name = slot.name.trim()
        return if (name.isNotEmpty() && slot.model != name) "${slot.model} · $name" else slot.model
    }

    // ── r27 轮值「发送后」三法 ─────────────────────────────────────────────

    /**
     * r27 F11：发送后冷却启动 + 流水线 stage 登记。router hook 在
     * [com.niki914.okia.hooks.Hooks.afterRequest]（请求刚打出去的时刻）调用。
     *
     * 原子武装 stage：属主 = 本键；解锁点 = now + delaySeconds * 1000
     * （delaySeconds=0 → now，延迟侧立即满足；>0 → 严格门控）；回答未收。
     * 下一次 [pickRevolverCandidate] 须等到「解锁点 AND 属主回答已收」都满足才放行。
     * 与 r25 不同：不再维护每键冷却表 / 未决集合——stage 就是上一发送键的冷却，
     * 严格序贯使跨键并行冷却没有意义（总吞吐由延迟之和封顶）。
     *
     * 计时器门控的是「下一个请求」，从不等当前这次（当前这次在 hook 的 beforeRequest 已立即发出）。
     */
    override suspend fun revolverMarkSent(configId: String, delaySeconds: Long) {
        revolverLock.withLock {
            val now = System.currentTimeMillis()
            stageArmed = true
            stageOwnerKey = configId
            stageGateUntilMs = if (delaySeconds > 0L) now + delaySeconds * 1000L else now
            stageAnswerReceived = false
            lastRoutedConfigId = configId
        }
        Logger.d(
            LOG_TAG,
            "revolver mark-sent model=$configId delaySeconds=$delaySeconds " +
                    "gateUntilMs=$stageGateUntilMs"
        )
    }

    /**
     * r27 F11：回答已收到（该请求的流已完整结束）。
     *
     * 两个调用源（均幂等）：
     *   1. [com.niki914.okia.hooks.Hooks.afterRequestStream] —— F17 新增的 per-LLM-request
     *      流终态，由 [com.niki914.okia.loop.RealAgentLoop] 在每个 tool-loop 段的
     *      [com.niki914.okia.loop.SegmentOutcome.Success] 上分发（一次请求完成时）。
     *   2. [com.niki914.zafiro.chat.LlmStreamPipeline.execute] 的 finally（回合终态）
     *      —— 失败 / 中止 / 无终态兜底时的安全网（已收割 / 未武装的 stage 上重放是 no-op）。
     *
     * **属主专属**：仅当被清者 == [stageOwnerKey] 时才抬起 [stageAnswerReceived]——
     * 别的键的迟到回答不碰本 stage（stage 必须等到「答案已收 AND 延迟走完」同时满足
     * 才由 [pickRevolverCandidate] 收割放行；在此处即清 stage 会绕过慢速回答、
     * 退化成各键并行，直接违背用户「总吞吐由各键延迟之和封顶」的规格）。
     */
    override suspend fun revolverAnswerReceived(configId: String) {
        revolverLock.withLock {
            if (stageArmed && configId == stageOwnerKey) {
                stageAnswerReceived = true
            }
        }
        Logger.d(
            LOG_TAG,
            "revolver answer-received model=$configId ownerMatch=${stageOwnerKey == configId} " +
                    "stageAnswerGateLifted=$stageAnswerReceived"
        )
    }

    /** r23：某配置的轮值冷却秒数（0 = 未设/无冷却）。供 router hook 取真实值传给 [revolverMarkSent]。 */
    override suspend fun revolverDelaySeconds(configId: String): Long =
        repo.llmConfigs.document().revolverDelaySeconds(configId)

    /** r23：最近一次 router hook 路由到的轮值配置 id（回合终态观察者据此登记回答已收到）。 */
    override suspend fun revolverLastRoutedConfigId(): String? = lastRoutedConfigId

    /** 用户 > provider /models > OpenRouter > fallback；空串/0/负值一律视为未设置。
     *  返回「值 to 取值来源」，来源只用于日志。 */
    private suspend fun resolveTokenCount(
        userText: String?,
        providerValue: suspend () -> Long?,
        openRouterValue: suspend () -> Long?,
        fallback: Long,
    ): Pair<Long, String> {
        userText?.let(TokenCountText::parse)?.takeIf { it > 0L }
            ?.let { return it to SOURCE_USER }
        providerValue()?.takeIf { it > 0L }?.let { return it to SOURCE_PROVIDER }
        openRouterValue()?.takeIf { it > 0L }?.let { return it to SOURCE_OPENROUTER }
        return fallback to SOURCE_DEFAULT
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = repo.mcp.list()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = repo.customPyTools.list()

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.customPyTools.save(tool, overwrite)
    }

    override suspend fun deleteCustomPyTool(name: String) {
        repo.customPyTools.delete(name)
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        repo.customPyTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return repo.builtinTools.list()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setGroupEnabled(groupId, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }

    override suspend fun listMaskingRules(): List<RuntimeMaskingRule> {
        return repo.maskingRules.list()
    }
}
