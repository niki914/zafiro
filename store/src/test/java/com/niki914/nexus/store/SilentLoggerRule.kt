package com.niki914.nexus.store

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Pure-JVM unit tests run without the Android framework: the default backend on
 * the Android source set ([com.niki914.logging.LogcatBackend]) would throw
 * "android.util.Log not mocked" whenever business code emits a log.
 *
 * Installs a no-op backend for the duration of each test so production
 * logging calls stay safe in unit tests.
 */
class SilentLoggerRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                Logger.install(object : Backend {
                    override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) {
                        // no-op
                    }
                })
                base.evaluate()
            }
        }
}
