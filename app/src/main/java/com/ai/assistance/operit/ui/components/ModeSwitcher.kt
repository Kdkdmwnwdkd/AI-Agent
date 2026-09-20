package com.ai.assistance.operit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.core.dualmode.OperitMode

/**
 * 顶部栏模式切换控件（Jetpack Compose）。
 *
 * 视觉设计：
 * - 类似 IDE Build Variant 切换器的紧凑按钮组
 * - 当前激活模式有背景色高亮
 * - 未激活模式为透明/文字色
 * - 单模式（SINGLE）时不显示任何控件（向后兼容）
 */
@Composable
fun ModeSwitcher(
    currentMode: OperitMode,
    isDualModeEnabled: Boolean,
    onModeChange: (OperitMode) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isDualModeEnabled) return

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeButton(
            label = "代码",
            isActive = currentMode == OperitMode.CODE,
            onClick = { onModeChange(OperitMode.CODE) }
        )

        ModeButton(
            label = "角色",
            isActive = currentMode == OperitMode.ROLE,
            onClick = { onModeChange(OperitMode.ROLE) }
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (isActive) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(horizontal = 2.dp)
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
