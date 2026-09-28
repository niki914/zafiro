package com.niki914.uikit.infra.preview

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet

@Composable
private fun OptionRowsContent(checkedTitle: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 16.dp),
    ) {
        OptionRow(
            title = "Photos",
            checked = checkedTitle == "Photos",
            leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
            onClick = {},
        )
        OptionRow(
            title = "Camera",
            checked = checkedTitle == "Camera",
            leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            onClick = {},
        )
        OptionRow(
            title = "File",
            checked = checkedTitle == "File",
            leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
            onClick = {},
        )
        OptionRow(
            title = "Folder",
            checked = checkedTitle == "Folder",
            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = {},
        )
    }
}

/** 只有行时的形态：确认行高、图标尾部间距、选中底与勾。 */
@Preview(name = "Option Rows Light", showBackground = true, widthDp = 420, heightDp = 420)
@Composable
private fun OptionRowsLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface { OptionRowsContent(checkedTitle = "Camera") }
    }
}

@Preview(
    name = "Option Rows Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 420,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OptionRowsDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface { OptionRowsContent(checkedTitle = null) }
    }
}

/** 完整单形态：标题 + header 插槽（下游可塞搜索框） + 内容槽 + G2 上圆角。 */
@Preview(name = "Option Sheet", showBackground = true, widthDp = 420, heightDp = 720)
@Composable
private fun OptionSheetPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Box(Modifier.fillMaxWidth()) {
            OptionSheet(
                visible = true,
                onDismissRequest = {},
                title = "Add to conversation",
                header = {
                    LiquidTextField(
                        value = "",
                        onValueChange = {},
                        placeholder = "Search",
                        singleLine = true,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                },
            ) { dismissThen ->
                Column { OptionRowStubList(dismissThen) }
            }
        }
    }
}

/** 预览里替代真实内容槽的最小列表：点一行即验收起动画 + 关闭回调链路。 */
@Composable
private fun OptionRowStubList(dismissThen: (() -> Unit) -> Unit) {
    val items = listOf("Photos", "Camera", "File", "Folder")
    items.forEach { title ->
        OptionRow(
            title = title,
            onClick = { dismissThen {} },
        )
    }
    Text(
        text = "dismissThen 会先播收起动画再回调关闭",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}
