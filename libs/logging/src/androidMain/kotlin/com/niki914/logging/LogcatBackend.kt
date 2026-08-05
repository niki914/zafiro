package com.niki914.logging

import android.util.Log

object LogcatBackend : Backend {
    override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) {
        when (level) {
            Level.VERBOSE -> if (throwable != null) Log.v(tag, msg, throwable) else Log.v(tag, msg)
            Level.DEBUG -> if (throwable != null) Log.d(tag, msg, throwable) else Log.d(tag, msg)
            Level.INFO -> if (throwable != null) Log.i(tag, msg, throwable) else Log.i(tag, msg)
            Level.WARN -> if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
            Level.ERROR -> if (throwable != null) Log.e(tag, msg, throwable) else Log.e(tag, msg)
        }
    }
}
