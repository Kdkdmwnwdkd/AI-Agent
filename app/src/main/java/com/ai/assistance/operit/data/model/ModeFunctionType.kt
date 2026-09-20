package com.ai.assistance.operit.data.model

/**
 * FunctionType 双模式扩展（与现有 FunctionType 并存）。
 *
 * 现有 FunctionType 用于"按功能分配不同模型配置"，
 * 新增 ModeFunctionType 用于"按模式分配不同行为（prompt/工具集）"。
 *
 * 由 MultiServiceManager 根据当前 OperitMode 映射到具体的模型配置。
 */
enum class ModeFunctionType {
    CODE_MODE_CHAT,       // 代码模式对话
    CODE_MODE_SUMMARY,    // 代码模式总结
    CODE_MODE_TOOL,       // 代码模式工具调用
    ROLE_MODE_CHAT,       // 角色模式对话
    ROLE_MODE_SUMMARY,    // 角色模式总结
    ROLE_MODE_MEMORY;     // 角色模式记忆检索

    companion object {
        /** 从 OperitMode 和原有 FunctionType 映射到 ModeFunctionType */
        fun from(mode: com.ai.assistance.operit.core.dualmode.OperitMode, baseType: FunctionType): ModeFunctionType {
            return when (mode) {
                com.ai.assistance.operit.core.dualmode.OperitMode.CODE -> {
                    when (baseType) {
                        FunctionType.CHAT -> CODE_MODE_CHAT
                        FunctionType.SUMMARY -> CODE_MODE_SUMMARY
                        else -> CODE_MODE_TOOL
                    }
                }
                com.ai.assistance.operit.core.dualmode.OperitMode.ROLE -> {
                    when (baseType) {
                        FunctionType.CHAT -> ROLE_MODE_CHAT
                        FunctionType.SUMMARY -> ROLE_MODE_SUMMARY
                        else -> ROLE_MODE_MEMORY
                    }
                }
                else -> CODE_MODE_CHAT  // SINGLE 模式下保持原有行为
            }
        }
    }
}
