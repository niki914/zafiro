package com.niki914.zafiro.chat.agentic.buildin

import kotlinx.coroutines.CancellationException

/**
 * Abstract base class for text-protocol tools.
 *
 * Subclasses implement [invokeText] to produce a [TextToolResult]. The base class
 * handles encoding via [TextToolResultCodec.encode] and wraps unexpected exceptions
 * into `UNKNOWN_ERROR` failure results. [CancellationException] is re-thrown unmodified.
 *
 * Because [TextResultBuiltinTool] extends [RawBuiltinTool], it satisfies
 * the [RawJsonBuiltinTool] contract — [BuiltinToolExecutor] needs no changes.
 */
abstract class TextResultBuiltinTool : RawBuiltinTool() {

    /**
     * Produces a typed [TextToolResult] for the given [request].
     *
     * This is the single method subclasses must implement. The result will be
     * automatically encoded by [invokeRaw] using [TextToolResultCodec.encode].
     */
    protected abstract suspend fun invokeText(request: BuiltinToolRequest): TextToolResult

    /**
     * Final override that orchestrates: [invokeText] -> [TextToolResult] -> encode.
     *
     * - If [invokeText] returns normally, the result is encoded and returned.
     * - If [invokeText] throws a [CancellationException], it is re-thrown.
     * - Any other [Throwable] is caught and encoded as an `UNKNOWN_ERROR` failure.
     */
    final override suspend fun invokeRaw(request: BuiltinToolRequest): String =
        try {
            TextToolResultCodec.encode(invokeText(request))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            TextToolResultCodec.encode(
                TextToolResult.failure(
                    code = "UNKNOWN_ERROR",
                    message = t.message ?: "Tool execution failed.",
                )
            )
        }
}
