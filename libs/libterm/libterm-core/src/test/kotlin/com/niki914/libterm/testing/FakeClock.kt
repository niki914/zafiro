package com.niki914.libterm.testing

import com.niki914.libterm.Clock

class FakeClock(initialMillis: Long = 0L) : Clock {
    private var currentMillis: Long = initialMillis

    override fun nowMillis(): Long = currentMillis

    fun setNowMillis(value: Long) {
        currentMillis = value
    }

    fun advanceBy(deltaMillis: Long): Long {
        require(deltaMillis >= 0L) { "deltaMillis must be >= 0" }
        currentMillis += deltaMillis
        return currentMillis
    }
}
