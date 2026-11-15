package com.niki914.zafiro.chat.agentic.device

import android.content.Intent
import android.net.Uri
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.util.runCatchingCancellable

object UriOpener {
    suspend fun open(uri: String): ShowResult {
        if (uri.isBlank()) {
            return ShowResult.Failed("MISSING_REQUIRED_FIELD")
        }
        val parsed = Uri.parse(uri)
        if (parsed.scheme.isNullOrBlank()) {
            return ShowResult.Failed("INVALID_URI")
        }
        return runCatchingCancellable {
            val context = ContextProvider.await().applicationContext
            val intent = Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ShowResult.Shown("URI opened.")
        }.getOrElse {
            ShowResult.Failed("URI_OPEN_FAILED")
        }
    }
}
