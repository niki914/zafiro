package com.niki914.okia.hooks

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpResponse

/**
 * 面向下游开发者的统一扩展面：自定义 Okia 在他们手中的表现。
 * 链式：注册多个实现按注册顺序执行，前一个的修改对后一个可见。
 * 命名 beforeXXX / afterXXX 成对（Xposed 风格）；每时机成对声明，
 * 默认空实现。全部 suspend 默认阻塞（调用方 await）；返回 Unit，
 * 可改数据走 mutation holder（write 记录签名字段）。
 * Design source: pi extensions（mutation 分发机制）、codex hooks、
 * Xposed 命名风格；注册位置在 config（hooks 列表只读，builder 累积）。
 */
interface Hooks {

    // 用户输入进入后：输入规范化 / 改写等 transform（异步注入已移出，见 §5.10）
    suspend fun beforeInput(input: InputHolder) {}

    // 用户输入处理完成（改写信息见 InputHolder.lastWriter）
    suspend fun afterInput(input: InputHolder) {}

    // 消息序列化前（协议无关层，数据脱敏主战场）
    suspend fun beforeSerialization(request: SerializationHolder) {}

    // 消息序列化后（拿到 Provider 请求）
    suspend fun afterSerialization(request: SerializationHolder, httpRequest: HttpRequest) {}

    // HttpEngine 发送前（http 层兜底脱敏 / 改写）
    suspend fun beforeRequest(request: HttpRequestHolder) {}

    // HttpEngine 发送后（拿到响应）
    suspend fun afterRequest(request: HttpRequestHolder, response: HttpResponse) {}

    // 工具执行前：审批 / 拦截 / 参数改写（阻断机制见开放问题 6.1）
    suspend fun beforeToolCall(call: ToolCallHolder) {}

    // 工具执行后：审计 / 埋点
    suspend fun afterToolCall(call: ToolCallHolder, result: ToolResultHolder) {}

    // 停止流程开始前（kill-then-stop 的 kill 步骤；每回合至多一次，§5.11）
    suspend fun beforeStop(calls: List<ContentBlock.ToolCall>) {}

    // 停止流程完成后（埋点统计）
    suspend fun afterStop(calls: List<ContentBlock.ToolCall>) {}
}
