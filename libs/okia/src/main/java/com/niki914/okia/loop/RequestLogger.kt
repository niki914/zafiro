package com.niki914.okia.loop

/**
 * 请求级诊断出口。okia 不依赖日志库（libs/okia 保持最小依赖），因此由宿主
 * 注入实现把每段 HTTP 请求的生命周期落进自己的日志。
 *
 * 用途：区分「一个请求在复读」与「app 在反复重发」——出问题时唯一能定案的信息。
 * 未注入（null）= 不打日志（库不强制宿主提供）。
 */
interface RequestLogger {

    /** 一段请求开始：segment 为回合内段序号，attempt 为该段内发送尝试，均从 1 起。 */
    fun started(segment: Int, attempt: Int) {}

    /**
     * 一段请求结束。elapsedMs 为该段耗时；stopReason 为终态结束原因（失败/超时为 null），
     * failed 标记该段以失败结束。
     */
    fun completed(
        segment: Int,
        attempt: Int,
        elapsedMs: Long,
        stopReason: String?,
        failed: Boolean
    ) {
    }
}
