package com.ai.assistance.operit.api.chat

/**
 * 聊天运行时插槽。
 *
 * - MAIN：主窗口
 * - FLOATING：浮窗（与 MAIN 保持同步，同一聊天的不同视图）
 */
enum class ChatRuntimeSlot {
    MAIN,
    FLOATING
}
