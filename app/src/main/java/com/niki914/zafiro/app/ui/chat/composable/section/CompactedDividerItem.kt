package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import kotlinx.coroutines.delay

@Composable
internal fun CompactedDividerItem(
    isCompacting: Boolean,
    modifier: Modifier = Modifier,
) {
    val dots = if (isCompacting) {
        val dotCount by produceState(initialValue = 1) {
            while (true) {
                delay(400)
                value = (value % 3) + 1
            }
        }
        ".".repeat(dotCount)
    } else {
        ""
    }

    Row(
        modifier = modifier
            .padding(vertical = 6.dp)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
        Text(
            text = if (isCompacting) {
                "${stringResource(R.string.ui_home_conversation_compacting)}$dots"
            } else {
                stringResource(R.string.ui_home_conversation_compacted)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
        )
    }
}
