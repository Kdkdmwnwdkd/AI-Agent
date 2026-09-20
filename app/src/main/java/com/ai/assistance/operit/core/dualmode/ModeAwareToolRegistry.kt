package com.ai.assistance.operit.core.dualmode

/**
 * 模式感知工具注册表（§6.5 工具隔离）。
 *
 * 安全边界：角色模式**不**暴露代码工具（shell、文件写等），
 * 防止"角色对话中意外执行危险命令"。
 */
object ModeAwareToolRegistry {

    /** 代码模式可用工具：全部工具 */
    val CODE_TOOLS = setOf(
        "read_file", "write_file", "run_shell", "git_status",
        "git_diff", "search_code", "list_dir", "apply_patch",
        "execute_command", "file_tree", "grep"
    )

    /** 角色模式可用工具：仅限对话相关 */
    val ROLE_TOOLS = setOf(
        "send_message", "memory_recall", "emotion_analyze",
        "image_recognition", "audio_recognition"
    )

    fun getToolsForMode(mode: OperitMode): Set<String> = when (mode) {
        OperitMode.CODE -> CODE_TOOLS
        OperitMode.ROLE -> ROLE_TOOLS
        else -> CODE_TOOLS + ROLE_TOOLS
    }

    /** 判断某工具在当前模式下是否可见 */
    fun isToolAvailable(mode: OperitMode, toolName: String): Boolean =
        getToolsForMode(mode).contains(toolName)
}
