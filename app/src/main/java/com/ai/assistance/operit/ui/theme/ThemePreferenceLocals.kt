package com.ai.assistance.operit.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.ActivePromptManager
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.ThemePreferenceSnapshot
import com.ai.assistance.operit.data.preferences.ThemePreferenceValues

val LocalThemePreferenceSnapshot =
    compositionLocalOf<ThemePreferenceSnapshot> {
        error("LocalThemePreferenceSnapshot is not provided.")
    }

/**
 * 冷启动首帧使用的主题快照。
 *
 * MainActivity 在 setContent 之前阻塞读取一次真实主题并注入，
 * 使首帧直接渲染用户配置，避免 defaultVisual()（系统动态色）先出现再切换。
 * 未注入时（预览、测试）回退为 null，行为与旧版一致。
 */
val LocalInitialThemeSnapshot = compositionLocalOf<ThemePreferenceSnapshot?> { null }

@Composable
fun rememberActiveThemePreferenceSnapshot(
    initialSnapshot: ThemePreferenceSnapshot? = null,
): ThemePreferenceSnapshot {
    val context = LocalContext.current
    val activePromptManager = remember(context) { ActivePromptManager.getInstance(context) }
    val themeSnapshot by activePromptManager.activeThemePreferenceSnapshotFlow.collectAsState(
        initial =
            initialSnapshot
                ?: ThemePreferenceSnapshot(
                    source = "character_card",
                    sourceId = CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                    values = ThemePreferenceValues.defaultVisual(),
                ),
    )
    return themeSnapshot
}
