package com.niki914.logging

// JVM 桌面无 release 构建概念，debug 判定源默认未注册时视为 debug，日志总是输出（level/scope 门控仍然生效）。
actual val defaultBackend: Backend = PrintBackend
