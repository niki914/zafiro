package com.niki914.zafiro.app.ui.preview.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.control.ZafiroSlider
import com.niki914.uikit.infra.component.SettingExpandableSliderItem
import com.niki914.uikit.scroll.ZafiroOverscrollContainer
import com.niki914.uikit.scroll.rememberZafiroOverscrollController

@Composable
private fun SliderThemeSample(title: String) {
    var continuousVal by remember { mutableFloatStateOf(0.45f) }
    var stepsVal by remember { mutableFloatStateOf(2f) }
    var settingSliderVal by remember { mutableFloatStateOf(1f) }
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

            // 1. 连续滑块 (Continuous)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "连续滑块: ${(continuousVal * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ZafiroSlider(
                    value = continuousVal,
                    onValueChange = { continuousVal = it },
                )
            }

            // 2. 离散步长滑块 (Discrete Steps = 3)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "离散刻度滑块 (Steps = 3): ${stepsVal.toInt()} / 4",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ZafiroSlider(
                    value = stepsVal,
                    onValueChange = { stepsVal = it },
                    valueRange = 0f..4f,
                    steps = 3,
                )
            }

            // 3. 禁用态滑块 (Disabled)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "禁用状态",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                ZafiroSlider(
                    value = 0.6f,
                    onValueChange = {},
                    enabled = false,
                )
            }

            // 4. 真实集成：设置页卡片内的可展开滑块
            Text(
                text = "设置卡片集成呈现：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            SettingExpandableSliderItem(
                title = "模型思考等级",
                summary = "调节大语言模型的多步推理深度与计算预算",
                value = settingSliderVal,
                onValueChange = { settingSliderVal = it },
                valueRange = 0f..3f,
                steps = 2,
                startLabel = "轻量",
                endLabel = "深度",
                initiallyExpanded = true,
            )
        }
    }
}

@Preview(
    name = "1. Liquid Glass - Slider",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun SliderLiquidPreview() {
    BaseTheme(themeStyle = ThemeStyle.LiquidGlass) {
        SliderThemeSample(title = "Liquid Glass 模式：手搓双层毛玻璃折射与弹性状态机")
    }
}

@Preview(
    name = "2. Material 3 (Classic) - Slider",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun SliderMaterial3Preview() {
    BaseTheme(themeStyle = ThemeStyle.Material3) {
        SliderThemeSample(title = "Material 3 模式：原生 M3 规范滑块 + 零 GPU 开销")
    }
}

@Preview(
    name = "3. Material 3 Expressive - Slider",
    showBackground = true,
    widthDp = 412,
    heightDp = 640,
)
@Composable
fun SliderMaterial3ExpressivePreview() {
    BaseTheme(themeStyle = ThemeStyle.Material3Expressive) {
        SliderThemeSample(title = "Material 3 Expressive 模式：PaletteStyle.Expressive 活力色系")
    }
}
