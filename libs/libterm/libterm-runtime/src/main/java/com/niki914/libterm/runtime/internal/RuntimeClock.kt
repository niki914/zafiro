package com.niki914.libterm.runtime.internal

import com.niki914.libterm.Clock

internal object RuntimeClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
