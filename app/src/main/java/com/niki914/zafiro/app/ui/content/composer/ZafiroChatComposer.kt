package com.niki914.zafiro.app.ui.content.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.niki914.uikit.textfield.ZafiroTextField
import com.niki914.uikit.textfield.ZafiroTextFieldStyle
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.composer.attach.ComposerAttachButton
import com.niki914.zafiro.app.ui.content.composer.send.ComposerSendButton
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage

/** 紧凑态 composer 最小高度：12dp 容器垂直 padding × 2 + 48sp 按钮 footprint。 */
internal val COMPACT_COMPOSER_MIN_HEIGHT = 72.dp

/** 展开态编辑区最大行数。 */
private const val EXPANDED_MAX_LINES = 7

@Composable
fun ZafiroChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    contextProgress: Float? = null,
    pendingImages: List<HomeChatImage> = emptyList(),
    pendingFiles: List<HomeChatFile> = emptyList(),
    onAttachImageClick: () -> Unit = {},
) {
    var expandedLatch by remember { mutableStateOf(false) }
    val expanded = value.contains('\n') || expandedLatch
    val onLayout: (TextLayoutResult?) -> Unit = { layout ->
        if (layout != null && !expandedLatch && layout.lineCount > 1) {
            expandedLatch = true
        }
    }
    LaunchedEffect(value) {
        if (value.isEmpty()) expandedLatch = false
    }

    ZafiroTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = if (expanded) null else stringResource(R.string.ui_home_input_placeholder),
        enabled = true,
        singleLine = false,
        maxLines = if (expanded) EXPANDED_MAX_LINES else 1,
        minHeight = COMPACT_COMPOSER_MIN_HEIGHT,
        contentVerticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
        onTextLayout = onLayout,
        expandedLayout = expanded,
        expandedActionsRow = {
            ComposerAttachButton(onClick = onAttachImageClick)
            ComposerSendButton(
                isGenerating = isGenerating,
                contextProgress = contextProgress,
                onSendClick = onSendClick,
                onStopClick = onStopClick,
            )
        },
        style = ZafiroTextFieldStyle.Composer,
        leadingContent = {
            ComposerAttachButton(onClick = onAttachImageClick)
        },
        trailingContent = {
            ComposerSendButton(
                isGenerating = isGenerating,
                contextProgress = contextProgress,
                onSendClick = onSendClick,
                onStopClick = onStopClick,
            )
        },
    )
}
