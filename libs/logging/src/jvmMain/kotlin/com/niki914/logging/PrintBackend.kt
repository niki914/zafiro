package com.niki914.logging

object PrintBackend : Backend {
    override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) {
        val line = "[$tag] $msg" + (throwable?.let { "\n${it.stackTraceToString()}" } ?: "")
        when (level) {
            Level.ERROR, Level.WARN -> System.err.println(line)
            else -> System.out.println(line)
        }
    }
}
