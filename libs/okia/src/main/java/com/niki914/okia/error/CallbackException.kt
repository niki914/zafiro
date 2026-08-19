package com.niki914.okia.error

/**
 * 业务 onEvent 回调抛出的非取消异常的包装。loop 捕获它并与协议流异常区分：
 * 事件分发失败 = host 侧代码问题（callback failure），分类为不可重试的
 * HookFailed——不伪装成网络错误（Transport）、不触发请求重发（否则同一次
 * LLM 请求被重发，产生重复计费 / 重复事件 / 工具副作用，同时掩盖业务错误）。
 */
class CallbackException(cause: Throwable) : Exception(cause)
