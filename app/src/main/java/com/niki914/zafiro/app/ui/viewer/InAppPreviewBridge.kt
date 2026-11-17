package com.niki914.zafiro.app.ui.viewer

import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

object InAppPreviewBridge {
    private val _requests = Channel<ViewerContent.Web>(Channel.BUFFERED)
    val requests: Flow<ViewerContent.Web> = _requests.receiveAsFlow()

    fun open(url: String) {
        findService<ApplicationService>()?.launchMainActivity()
        _requests.trySend(ViewerContent.Web(url = url))
    }
}
