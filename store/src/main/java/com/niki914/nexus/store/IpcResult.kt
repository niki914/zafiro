package com.niki914.nexus.store

sealed interface IpcReadResult {
    data class Success(val json: String) : IpcReadResult
    data object Unreachable : IpcReadResult
    data object NotFound : IpcReadResult
}

sealed interface IpcWriteResult {
    data object Success : IpcWriteResult
    data object Unreachable : IpcWriteResult
}

data class IpcMutateResult(
    val writeResult: IpcWriteResult,
    val updatedJson: String?,
)
