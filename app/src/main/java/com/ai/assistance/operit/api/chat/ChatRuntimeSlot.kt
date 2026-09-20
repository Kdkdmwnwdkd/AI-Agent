package com.ai.assistance.operit.api.chat

/**
 * ChatRuntimeSlot 扩展（v1.0.1g 双模式）。
 *
 * 原有插槽：
 * - MAIN：主窗口
 * - FLOATING：浮窗（与 MAIN 保持同步，同一聊天的不同视图）
 *
 * 新增模式插槽：
 * - CODE_MODE：代码模式专用插槽
 * - ROLE_MODE：角色模式专用插槽
 *
 * CODE_MODE 和 ROLE_MODE 之间**不**互相同步（隔离），
 * 但各自内部仍支持 MAIN ↔ FLOATING 多窗口同步。
 */
enum class ChatRuntimeSlot {
    MAIN,
    FLOATING,
    CODE_MODE,
    ROLE_MODE
}
