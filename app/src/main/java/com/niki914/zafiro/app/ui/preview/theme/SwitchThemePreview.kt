package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.control.ZafiroSwitch
import com.niki914.uikit.infra.component.SettingToggleItem

@Composable
private fun SwitchThemeSample(title: String) {
    var checked1 by remember { mutableStateOf(false) }
    var checked2 by remember { mutableStateOf(true) }
    var itemChecked by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 1. 独立开关组（关、开、带图标/禁用）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 关
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "关",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ZafiroSwitch(
                        checked = checked1,
                        onCheckedChange = { checked1 = it },
                    )
                }

                // 开
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ZafiroSwitch(
                        checked = checked2,
                        onCheckedChange = { checked2 = it },
                    )
                }

                // 带图标（Checked 时显示 Check 图标）
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "带图标",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ZafiroSwitch(
                        checked = checked2,
                        onCheckedChange = { checked2 = it },
                        thumbContent = if (checked2) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            }
                        } else null,
                    )
                }

                // 禁用
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "禁用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ZafiroSwitch(
                        checked = true,
                        onCheckedChange = {},
                        enabled = false,
                    )
                }
            }

            // 2. 真实集成：设置页卡片内的开关
            Text(
                text = "设置卡片集成呈现：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SettingToggleItem(
                title = "启用实验性特性",
                description = "开启后可在对话中体验高阶工具调用与多模态流式推理",
                checked = itemChecked,
                onCheckedChange = { itemChecked = it },
            )
        }
    }
}

@Preview(
    name = "1. Liquid Glass - Switch",
    showBackground = true,
    widthDp = 412,
    heightDp = 300,
)
@Composable
fun SwitchLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        SwitchThemeSample(title = "Liquid Glass 模式：手搓双层毛玻璃折射与弹性状态机")
    }
}

@Preview(
    name = "2. Material 3 (Classic) - Switch",
    showBackground = true,
    widthDp = 412,
    heightDp = 300,
)
@Composable
fun SwitchMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        SwitchThemeSample(title = "Material 3 模式：原生 M3 规范开关 + 零 GPU 开销")
    }
}

@Preview(
    name = "3. Material 3 Expressive - Switch",
    showBackground = true,
    widthDp = 412,
    heightDp = 300,
)
@Composable
fun SwitchMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        SwitchThemeSample(title = "Material 3 Expressive 模式：PaletteStyle.Expressive 活力色系")
    }
}
