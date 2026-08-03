package com.niki914.libterm.runtime

import com.niki914.libterm.AuthorizationMode
import com.niki914.libterm.OutputChunk

class LibTermRuntimeConfig {
    var defaultAuthorizationMode: AuthorizationMode = AuthorizationMode.REQUEST_IF_NEEDED
    var defaultCwd: String? = null
    var outputDecoder: TerminalOutputDecoder = AnsiStrippingTerminalOutputDecoder()
    var outputDecode: ((OutputChunk) -> String)? = null
}
