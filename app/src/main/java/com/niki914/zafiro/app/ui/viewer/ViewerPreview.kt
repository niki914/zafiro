package com.niki914.zafiro.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.zafiro.app.ui.chat.composable.attachment.HomeChatImageCard
import com.niki914.zafiro.app.ui.model.home.HomeChatImage

@Preview(
    name = "Viewer Image Unavailable - Light",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun ViewerImageUnavailableLightPreview() {
    BaseTheme(darkTheme = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ImageUnavailableFallback(
                path = "/storage/emulated/0/Android/data/com.niki914.zafiro/cache/sample_expired.jpg",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(
    name = "Viewer Image Unavailable - Dark",
    showBackground = true,
    widthDp = 390,
    heightDp = 844,
)
@Composable
private fun ViewerImageUnavailableDarkPreview() {
    BaseTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            ImageUnavailableFallback(
                path = "/storage/emulated/0/Android/data/com.niki914.zafiro/cache/sample_expired.jpg",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(
    name = "Chat Attachment Card Unavailable",
    showBackground = true,
)
@Composable
private fun ChatAttachmentCardUnavailablePreview() {
    BaseTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(16.dp)) {
            HomeChatImageCard(
                image = HomeChatImage(
                    id = "preview_1",
                    path = "/storage/emulated/0/Android/data/com.niki914.zafiro/cache/sample_expired.jpg",
                ),
                size = 120.dp,
                cornerRadius = 16.dp,
                onRemove = null,
            )
        }
    }
}
