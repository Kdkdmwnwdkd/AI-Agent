package com.ai.assistance.operit.core.dualmode

/**
 * Operit 双模式枚举（v1.0.1g 集成）。
 *
 * - SINGLE：兼容模式，保持原有单模式行为
 * - CODE：代码模式（编程助手，完整工具集）
 * - ROLE：角色模式（AI 伴侣/角色扮演，仅限对话工具）
 */
enum class OperitMode {
    SINGLE,
    CODE,
    ROLE;

    val displayName: String
        get() = when (this) {
            SINGLE -> "单模式"
            CODE -> "代码"
            ROLE -> "角色"
        }
}
