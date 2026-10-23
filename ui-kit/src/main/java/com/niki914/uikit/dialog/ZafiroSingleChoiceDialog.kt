package com.niki914.uikit.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingsItemSurface
import com.niki914.uikit.infra.shape.G2FieldShape

@Composable
fun <T> ZafiroSingleChoiceDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    options: List<T>,
    selectedId: String?,
    optionId: (T) -> String,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    cancelButtonText: String = stringResource(android.R.string.cancel),
    dismissOnBackgroundTap: Boolean = true,
) {
    ZafiroDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnBackgroundTap = dismissOnBackgroundTap,
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        content = {
            val highlightShape = remember { G2FieldShape(16.dp) }
            options.forEach { option ->
                val isSelected = optionId(option) == selectedId
                key(optionId(option)) {
                    SettingsItemSurface(
                        onClick = { onSelect(option) },
                        shape = highlightShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        minHeight = 48.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = if (isSelected) {
                                Icons.Default.RadioButtonChecked
                            } else {
                                Icons.Default.RadioButtonUnchecked
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            },
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = optionLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        actions = {
            ZafiroDialogButton(
                text = cancelButtonText,
                onClick = onDismissRequest,
                isPrimary = false,
            )
        },
    )
}
