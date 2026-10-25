package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.component.SettingExpandableTextCard
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.scroll.ZafiroOverscrollContainer
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.uikit.textfield.ZafiroOutlinedTextField
import com.niki914.uikit.textfield.ZafiroSecretTextField
import com.niki914.uikit.textfield.ZafiroTextField

@Composable
private fun TextFieldThemeSample(title: String) {
    var proxyValue by remember { mutableStateOf("http://127.0.0.1:7890") }
    var promptValue by remember {
        mutableStateOf("你是一个资深全栈工程师，负责协助用户完成高效的架构设计与代码重构。")
    }
    var apiKeyValue by remember { mutableStateOf("sk-ant-api03-abcdef1234567890") }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var dialogInputValue by remember { mutableStateOf("用户要求记忆：经常喝美式咖啡") }

    val overscrollController = rememberZafiroOverscrollController()
    val scrollState = rememberScrollState()

    ZafiroOverscrollContainer(
        overscrollController = overscrollController,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 1. 设置项卡片展开：单行外边框输入框 (Proxy 示例)
            SettingExpandableTextCard(
                title = "代理服务器 (Proxy)",
                description = "配置 HTTP/SOCKS 转发代理地址与端口",
                value = proxyValue,
                onValueChange = { proxyValue = it },
                placeholder = "例如：http://127.0.0.1:7890",
                minLines = 1,
                maxLines = 1,
                initiallyExpanded = true,
            )

            // 2. 设置项卡片展开：多行文本框 (System Prompt 示例)
            SettingExpandableTextCard(
                title = "系统提示词 (System Prompt)",
                description = "定义模型的角色、回答规范与约束条件",
                value = promptValue,
                onValueChange = { promptValue = it },
                placeholder = "请输入多行系统设定",
                minLines = 3,
                maxLines = 6,
                initiallyExpanded = true,
            )

            // 3. 设置项专属：秘钥密码外边框输入框 (API Key 示例)
            SettingsGroupCard(title = "设置项专属基件 (ZafiroSecretTextField)") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "API Key 秘钥输入（Material 纯外边框，带眼睛切换）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ZafiroSecretTextField(
                        value = apiKeyValue,
                        onValueChange = { apiKeyValue = it },
                        placeholder = "sk-...",
                        visible = apiKeyVisible,
                        onToggleVisibility = { apiKeyVisible = !apiKeyVisible },
                        toggleVisibleContentDescription = "隐藏秘钥",
                        toggleHiddenContentDescription = "显示秘钥",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 4. 对话框专属：贴底纯下划线输入框
            SettingsGroupCard(title = "对话框专属基件 (ZafiroTextField)") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "对话框输入框（Material 纯贴底下划线，零背景框）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ZafiroTextField(
                        value = dialogInputValue,
                        onValueChange = { dialogInputValue = it },
                        placeholder = "输入记忆内容...",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 5. 设置项通用外边框输入框
            SettingsGroupCard(title = "设置项独立外边框基件 (ZafiroOutlinedTextField)") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "模型名称（Material 纯外边框）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ZafiroOutlinedTextField(
                        value = "DeepSeek-V3",
                        onValueChange = {},
                        placeholder = "输入模型标识",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Preview(
    name = "1. Liquid Glass",
    showBackground = true,
    widthDp = 412,
    heightDp = 860,
)
@Composable
fun TextFieldLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        TextFieldThemeSample(
            title = "Liquid Glass 模式：圆角毛玻璃槽包裹 + 内部独立 rememberLayerBackdrop 隔离（零 Crash）",
        )
    }
}

@Preview(
    name = "2. Material 3 (Classic)",
    showBackground = true,
    widthDp = 412,
    heightDp = 860,
)
@Composable
fun TextFieldMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        TextFieldThemeSample(
            title = "Material 3 模式：设置项纯外边框（Outlined） + 对话框纯贴底线（Underline），零灰底色块",
        )
    }
}

@Preview(
    name = "3. Material 3 Expressive",
    showBackground = true,
    widthDp = 412,
    heightDp = 860,
)
@Composable
fun TextFieldMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        TextFieldThemeSample(
            title = "Material 3 Expressive 模式：Spring 弹性触感反馈 + 纯边框/纯下划线",
        )
    }
}
