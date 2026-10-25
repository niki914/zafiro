package com.niki914.zafiro.app.ui.content.composer.attach

import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niki914.uikit.button.ZafiroActionButton

@Composable
internal fun ComposerAttachButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.primary

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        ZafiroActionButton(
            onClick = onClick,
            modifier = modifier.offset(x = (-6).dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Button",
            )
        }
    }
}
