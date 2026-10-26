package com.niki914.zafiro.business.remote.ledger

import java.io.File
import java.io.FileNotFoundException

internal sealed interface LedgerLoad {
    data class Ready(val state: LedgerState) : LedgerLoad
    data object Missing : LedgerLoad
    data object Unreadable : LedgerLoad
}

internal interface LedgerStore {
    suspend fun load(): LedgerLoad
    suspend fun save(state: LedgerState)
}

/** `filesDir/telemetry/ledger.json`。调用方传入 telemetry 目录。 */
internal class FileLedgerStore(directory: File) : LedgerStore {
    private val file = File(directory, LEDGER_FILE_NAME)

    override suspend fun load(): LedgerLoad {
        if (!file.exists()) return LedgerLoad.Missing
        val text = try {
            AtomicText.read(file)
        } catch (_: FileNotFoundException) {
            return LedgerLoad.Missing
        }
        val state = LedgerJson.decode(text) ?: return LedgerLoad.Unreadable
        return LedgerLoad.Ready(state)
    }

    override suspend fun save(state: LedgerState) {
        AtomicText.write(file, LedgerJson.encode(state))
    }

    private companion object {
        const val LEDGER_FILE_NAME = "ledger.json"
    }
}
