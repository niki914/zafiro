package com.niki914.zafiro.app.ui.home.composer.attach

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.zafiro.app.R

@Composable
internal fun HomeAttachmentSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    onPhotosClick: () -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onFolderClick: () -> Unit,
) {
    OptionSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_home_attach_sheet_title),
    ) { dismissThen ->
        OptionRow(
            title = stringResource(R.string.ui_home_attach_photos),
            leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
            onClick = {
                dismissThen { onPhotosClick() }
            },
        )
        OptionRow(
            title = stringResource(R.string.ui_home_attach_camera),
            leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
            onClick = {
                dismissThen { onCameraClick() }
            },
        )
        OptionRow(
            title = stringResource(R.string.ui_home_attach_file),
            leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
            onClick = {
                dismissThen { onFileClick() }
            },
        )
        OptionRow(
            title = stringResource(R.string.ui_home_attach_folder),
            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
            onClick = {
                dismissThen { onFolderClick() }
            },
        )
    }
}
