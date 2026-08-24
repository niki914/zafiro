package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.TintLiquidButton
import com.niki914.uikit.infra.liquidScreenHazeSource
import com.niki914.uikit.infra.liquidScreenTopPadding

@Composable
fun DonePageContent(
    onEnterHome: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .liquidScreenHazeSource()
            .padding(top = liquidScreenTopPadding())
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(modifier = Modifier.weight(1f))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_onboard_done_headline),
                    style = TextStyle(
                        fontSize = 36.sp,
                        lineHeight = 40.sp,
                        letterSpacing = (-1.2).sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.ui_onboard_done_description),
                    style = TextStyle(
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        letterSpacing = 0.1.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TintLiquidButton(
                text = stringResource(R.string.ui_onboard_done_enter_home),
                onClick = onEnterHome,
            )
        }
    }
}
