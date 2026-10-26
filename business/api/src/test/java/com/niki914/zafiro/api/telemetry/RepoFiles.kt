package com.niki914.zafiro.api.telemetry

import java.io.File

internal fun repoRoot(): File {
    var dir = File(System.getProperty("user.dir"))
    while (true) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
    }
}

internal fun contractYamlFile(): File = File(repoRoot(), "telemetry-contract/contract.yaml")

internal fun telemetryContractFile(): File = File(
    repoRoot(),
    "business/api/src/main/java/com/niki914/zafiro/api/telemetry/TelemetryContract.kt",
)
