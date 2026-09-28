package com.niki914.uikit.infra.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape
import kotlinx.coroutines.launch

/**
 * 通用底部选项单：标题 + 固定头部槽 + 内容槽。
 *
 * - [visible] 为 false 时不组合任何内容（调用方不必自己判断）。
 * - 点选项统一走 [content] 拿到的 `dismissThen`：先播 M3 收起动画，再回调
 *   [onDismissRequest] 与动作，避免 sheet 闪现消失。
 * - [bodyModifier] 透传给「标题 + header + content」的容器，调用方按需加高度约束
 *   （例如把单锁在屏高 50%~60%）。
 * - 单圆角用 G2，与 composer / 对话框 / 卡片同语言；sheet 在独立窗口里，
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    bodyModifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.(dismissThen: (() -> Unit) -> Unit) -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // 只圆上面两角：底边贴在屏幕外
        shape = G2FieldShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 0.dp, bottomStart = 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column(modifier = bodyModifier.fillMaxWidth()) {
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                header()
                content { action ->
                    scope.launch {
                        sheetState.hide()
                        onDismissRequest()
                        action()
                    }
                }
            }
        }
    }
}

/**
 * 选项单里的一行：G2 圆角 + 按下底色，[checked] 时用对比色底 + 勾。
 * [leadingContent] 供调用方在标题前塞图标（行内自带尾部间距）。
 */
@Composable
fun OptionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    // 选中态 = 对比色底 + G2 圆角框，文字换 onContainer 保可读；未选中透明。
    // clip 保证按下底色与选中底色同形状（SettingsItemSurface 自身无 shape 参数）。
    val highlightShape = G2FieldShape(16.dp)
    SettingsItemSurface(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        minHeight = 64.dp,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(highlightShape)
            .then(
                if (checked) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                },
            ),
    ) {
        if (leadingContent != null) {
            Box(Modifier.padding(end = 16.dp)) { leadingContent() }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }
    }
}
