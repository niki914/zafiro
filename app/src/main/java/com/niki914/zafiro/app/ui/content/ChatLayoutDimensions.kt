package com.niki914.zafiro.app.ui.content

import androidx.compose.ui.unit.dp

// ── 块 UI 密度参数表（折叠块 + 命令型工具正文共用）────────────────
// 调视觉密度只改这一处；使用处按名引用或组合计算。不要散落魔法数字。
internal val BlockSpacing = 12.dp      // 统一块间距：头部↔展开内容、多工具链行间、命令↔输出缝隙

// ======
internal val BlockHeaderInsetCollapsed = 16.dp // 呼吸：收起态 header 水平内收（收得深）
internal val BlockHeaderInsetExpanded = 12.dp  // 呼吸：展开态 header 水平放开；header 左右 padding 下限 12dp
internal val BlockContainerPadY = 12.dp   // 容器纵向内收（折叠行上下呼吸；卡片底部对称基准）
internal val BlockIconDot = 24.dp        // 图标 tonal 底 / 尾部槽位
internal val BlockRowGap = 8.dp          // 图标↔标题、标题↔尾部
internal val BlockRowIcon = 13.dp        // 折叠行图标
internal val BlockRowChevron = 18.dp     // 折叠行箭头（与 loader / 左图标视觉相近）
internal val BlockLoaderSize = 20.dp     // 运行中 M3E LoadingIndicator（装在尾部圆底内）
internal val BlockCircularLoaderSize = 16.dp // 运行中经典/Liquid CircularProgressIndicator

internal val BlockContentInset = BlockHeaderInsetExpanded + BlockIconDot / 2
internal val BlockContentTopGap = BlockContainerPadY // 标题底部→内容顶部 = 标题顶部→卡缘，纵向节奏均一（12-12-12）
internal val BlockContentBottomGap = BlockContainerPadY // 展开内容离容器下缘 = header 上方内收，上下对称
internal val CommandPanelPadX = 12.dp // 命令面板左右内收
internal val CommandRowPadY = 4.dp    // 命令行纵向
internal val OutputPadY = 6.dp        // 输出区纵向
internal val ResultScrollMaxHeight = 102.dp // 工具结果/思考正文滚动高度上限
