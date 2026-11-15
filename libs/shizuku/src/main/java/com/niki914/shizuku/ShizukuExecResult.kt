package com.niki914.shizuku

/**
 * Shizuku 命令执行结果。
 */
data class ShizukuExecResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = exitCode == 0
}
