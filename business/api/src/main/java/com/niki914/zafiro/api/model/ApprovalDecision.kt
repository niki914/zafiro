package com.niki914.zafiro.api.model

/**
 * 授权裁决者的回答。来源并发询问，第一个非弃权者结算。
 *
 * [Abstain] 是多来源机制的关键：本来源不处理这次请求（界面不可见、
 * 不具备呈现能力），交给其他来源；全部来源都弃权或没有来源时拒绝。
 */
enum class ApprovalDecision {
    Allow,
    Deny,

    /** 本来源不处理这次请求，交给其他来源。 */
    Abstain,
}
