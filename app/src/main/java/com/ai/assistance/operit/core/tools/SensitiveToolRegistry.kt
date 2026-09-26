package com.ai.assistance.operit.core.tools

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.util.AppLogger

/**
 * Phase 5: Sensitive Tool Registry
 *
 * 安全红线：管理可能执行破坏性/高风险操作的敏感工具白名单。
 * 敏感工具首次执行前需用户显式授权，授权后写入白名单持久化存储。
 *
 * 敏感工具清单（基于工具名称关键词匹配）：
 * - 终端/Shell: shell_command, adb_shell, terminal_*, exec_*
 * - 文件系统: file_delete, file_write, folder_delete, chmod, rm, mv (overwrite)
 * - 浏览器: browser_close_all, browser_clear_data, browser_evaluate_js
 * - 系统: reboot, shutdown, airplane_mode, install_apk, uninstall_apk
 * - 蓝牙: ble_disconnect, ble_remove_bond
 * - 网络: curl (PUT/DELETE/POST with destructive endpoints)
 *
 * 非敏感工具（始终允许）：
 * - 只读操作: file_read, folder_list, browser_open, search
 * - 信息查询: weather, time, calculator, memory_read
 */
object SensitiveToolRegistry {

    private const val TAG = "SensitiveToolRegistry"
    private const val PREFS_NAME = "sensitive_tool_whitelist"

    /** 敏感工具模式匹配规则（正则） */
    private val SENSITIVE_PATTERNS = listOf(
        // 终端/命令执行
        Regex("^(shell_|adb_shell|terminal_|exec_|run_command|cmd_)"),
        // 文件破坏性操作
        Regex("^(file_delete|folder_delete|rmdir|chmod|rm_|mv_|overwrite_)"),
        // 浏览器破坏性
        Regex("^(browser_close_all|browser_clear_|browser_evaluate_js)"),
        // 系统级操作
        Regex("^(reboot|shutdown|airplane_mode|install_apk|uninstall_apk|system_)"),
        // 蓝牙破坏性
        Regex("^(ble_disconnect|ble_remove_bond)"),
        // 网络破坏性（排除安全HTTP方法到危险端点）
        Regex("^(curl_put|curl_delete|http_delete|send_request_destructive)")
    )

    /** 已明确确认为非敏感的工具（白名单绕过） */
    private val ALWAYS_SAFE = setOf(
        "shell_command_readonly", "adb_shell_info", "file_read", "folder_list",
        "browser_open", "browser_navigate", "search", "weather", "time",
        "calculator", "memory_read", "memory_search"
    )

    /** 检查工具是否属于敏感工具 */
    fun isSensitive(toolName: String): Boolean {
        if (toolName in ALWAYS_SAFE) return false
        return SENSITIVE_PATTERNS.any { it.matches(toolName) }
    }

    /** 检查工具是否已在白名单中（已授权） */
    fun isWhitelisted(context: Context, toolName: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(toolName, false)
    }

    /** 将工具加入白名单（用户授权后调用） */
    fun whitelistTool(context: Context, toolName: String) {
        AppLogger.i(TAG, "Tool whitelisted: $toolName")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(toolName, true)
            .apply()
    }

    /** 从白名单移除（撤销授权） */
    fun removeFromWhitelist(context: Context, toolName: String) {
        AppLogger.i(TAG, "Tool removed from whitelist: $toolName")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(toolName)
            .apply()
    }

    /** 获取所有已授权工具列表 */
    fun getWhitelistedTools(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.keys.filter { prefs.getBoolean(it, false) }.toSet()
    }

    /** 清空所有授权（紧急重置） */
    fun clearAll(context: Context) {
        AppLogger.w(TAG, "All sensitive tool authorizations cleared")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
