package com.niki914.libterm.runtime

interface TerminalOutputDecoder {
    fun createSessionDecoder(): SessionTerminalOutputDecoder
}
