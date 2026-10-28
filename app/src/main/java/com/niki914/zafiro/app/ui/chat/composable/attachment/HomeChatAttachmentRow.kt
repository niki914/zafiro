package com.niki914.zafiro.app.ui.chat.composable.attachment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.formatFileNameMiddleTruncated
import com.niki914.zafiro.app.ui.helper.PathBitmapState
import com.niki914.zafiro.app.ui.helper.rememberPathBitmapState

/**
 * 卡片的顶部遮罩 + 右上角关闭钮（图片卡 / 文件卡共用）。
 *
 * BoxScope 扩展：调用方是卡片的 Box，两者都对齐在卡片里。遮罩是卡片高度 30% 的
 * 纵向渐变（black 50% → 0%），用来衬出白色关闭钮。
 */
@Composable
private fun BoxScope.CardRemoveOverlay(
    size: Dp,
    contentDescription: String,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(size * 0.3f)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    ),
                )
            },
    )
    Icon(
        imageVector = Icons.Default.Close,
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .size(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRemove,
            ),
    )
}

/**
 * 单张图片卡。尺寸由调用方决定（composer 待发 60dp / 消息内大图卡 / 工具结果预览）。
 * 顶部 30% 纵向渐变遮罩（black 50% → 0%），右上角白色关闭钮（可选），无圆形背景。
 */
@Composable
internal fun HomeChatImageCard(
    image: HomeChatImage,
    size: Dp,
    cornerRadius: Dp,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = G2CardShape(cornerRadius)
    val state = rememberPathBitmapState(image.path)
    val cardModifier = modifier
        .size(size)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
            } else {
                Modifier
            }
        )

    Box(modifier = cardModifier) {
        when (state) {
            PathBitmapState.Loading -> Unit
            is PathBitmapState.Success -> {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            PathBitmapState.Error -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = "Broken Image Icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(if (size >= 100.dp) 32.dp else 20.dp),
                    )
                    if (size >= 100.dp) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.ui_image_unavailable),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (onRemove != null) {
            CardRemoveOverlay(
                size = size,
                contentDescription = "Remove Image Icon",
                onRemove = onRemove,
            )
        }
    }
}

/**
 * 单个文件 / 文件夹卡。
 *
 * 紧凑态（待发条 60dp）：左上角圆角图标徽章 + 底部居左单行截断文字（加大底部边距）+ 右上角关闭钮。
 * 展开态（消息气泡 120dp）：左上角 36dp 徽章 + 底部加粗文件名 + 粗粒度分类副标题（多语言）。
 */
@Composable
internal fun HomeChatFileCard(
    file: HomeChatFile,
    size: Dp,
    cornerRadius: Dp,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = G2CardShape(cornerRadius)
    val category = file.category
    val isCompact = size <= 80.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (isCompact) {
            // 待发条 60dp：纯图标（无背景容器，尺寸放大至 20dp）+ 底部单行截断文字
            val displayName = formatFileNameMiddleTruncated(file.name, 11)
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
                    .size(20.dp),
            )
            Text(
                text = displayName,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        } else {
            // 消息卡片 120dp：实心图标（30dp）+ 底部 2 行文件名（字号 12sp）+ 粗粒度分类说明（完全展示不截断）
            val displayName = formatFileNameMiddleTruncated(file.name, 32)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = displayName,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(category.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }

        if (onRemove != null) {
            CardRemoveOverlay(
                size = size,
                contentDescription = "Remove File Icon",
                onRemove = onRemove,
            )
        }
    }
}

/**
 * 附件行（composer 待发条 / 用户消息附件区共用）：图片与文件**同一行**横向滚动。
 *
 * 两种卡**同尺寸同形状**（同一个 [G2CardShape]、同一圆角、同一方形边长）：
 * 预览区尺寸不一很跳跃，而文件名可以在卡内省略。
 * 外层以与卡片相同的 G2 圆角 clip——边缘卡被裁切时仍呈现圆角形态。宽度约束由调用方给。
 */
@Composable
fun HomeChatAttachmentRow(
    images: List<HomeChatImage>,
    files: List<HomeChatFile>,
    cardSize: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    onRemoveImage: ((String) -> Unit)? = null,
    onRemoveFile: ((String) -> Unit)? = null,
    onImageClick: ((HomeChatImage) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // 宽度由调用方决定（贴内容宽或撑满）：不加 fillMaxWidth，
            // 否则消息附件行无法右对齐贴内容宽
            .clip(G2CardShape(cornerRadius))
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            HomeChatImageCard(
                image = image,
                size = cardSize,
                cornerRadius = cornerRadius,
                onRemove = onRemoveImage?.let { remove -> { remove(image.id) } },
                onClick = onImageClick?.let { click -> { click(image) } },
            )
        }
        files.forEach { file ->
            HomeChatFileCard(
                file = file,
                size = cardSize,
                cornerRadius = cornerRadius,
                onRemove = onRemoveFile?.let { remove -> { remove(file.id) } },
            )
        }
    }
}
