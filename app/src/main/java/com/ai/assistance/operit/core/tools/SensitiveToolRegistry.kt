package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.util.AppLogger

/**
 * Phase 5: Sensitive Tool Registry
 *
 * 安全红线：管理可能执行破坏性/高风险操作的敏感工具白名单。
 * 敏感工具首次执行前需用户显式授权，授权后写入白名单持久化存储。
 *
 * 使用显式工具名称集合替代正则匹配，避免 PatternSyntaxException 和误判。
 * 工具名称来源：ToolRegistration.kt 中 registerTool(name = "...") 注册的全部工具。
 */
object SensitiveToolRegistry {

    private const val TAG = "SensitiveToolRegistry"
    private const val PREFS_NAME = "sensitive_tool_whitelist"

    /**
     * 高风险工具：可能造成不可逆破坏或系统级影响，始终需要用户确认。
     * 即使 master switch 设为 ALLOW，这些工具仍会弹出确认。
     */
    private val HIGH_RISK_TOOLS = setOf(
        // 终端 / Shell 命令执行
        "execute_shell",
        "execute_in_terminal_session",
        "execute_in_terminal_session_streaming",
        "execute_hidden_terminal_command",
        "input_in_terminal_session",
        "execute_sandbox_script_direct",

        // 文件删除 / 破坏性文件操作
        "delete_file",
        "delete_memory",
        "delete_memory_link",
        "delete_model_config",
        "delete_character_card",
        "delete_workflow",
        "delete_chat",
        "move_file", // 可能覆盖目标文件

        // 文件写入
        "write_file",
        "write_file_binary",
        "apply_file",
        "edit_file",
        "create_file",

        // 应用管理
        "install_app",
        "uninstall_app",
        "stop_app",

        // 系统级操作
        "modify_system_setting",
        "send_broadcast",
        "restart_mcp_with_logs",

        // 浏览器破坏性
        "browser_close_all",
        "browser_evaluate",
        "browser_run_code",
        "close_all_virtual_displays",

        // 网络（可能触发服务端副作用）
        "http_request",
        "multipart_request",

        // 蓝牙连接
        "bluetooth_connect",
        "bluetooth_listen",
        "bluetooth_accept",
        "request_enable_bluetooth",

        // 沙箱配置
        "set_sandbox_package_enabled",

        // 通知（可能打扰用户）
        "send_notification",

        // 分享（泄露文件到外部应用）
        "share_file",

        // 写入环境变量
        "write_environment_variable"
    )

    /**
     * 明确安全的工具：纯只读操作，永远不会弹出确认。
     */
    private val ALWAYS_SAFE_TOOLS = setOf(
        // 文件读取
        "read_file", "read_file_part", "read_file_full", "read_file_binary",
        "list_files", "file_exists", "file_info", "find_files",
        "grep_code", "grep_context",
        "copy_file", "make_directory", "zip_files", "unzip_files",
        "download_file", "open_file",

        // 浏览器只读
        "browser_snapshot", "browser_take_screenshot", "browser_console_messages",
        "browser_network_requests", "browser_navigate", "browser_navigate_back",
        "browser_tabs", "browser_hover", "browser_wait_for", "browser_select_option",
        "browser_resize",

        // 记忆查询
        "query_memory", "get_memory_by_title", "query_memory_links",

        // 配置查询
        "list_model_configs", "list_function_model_configs",
        "get_function_model_config", "list_character_cards", "get_character_card",
        "list_chats", "find_chat", "get_chat_messages", "get_chat_messages_range",
        "list_installed_apps", "get_notifications", "get_app_usage_time",

        // 系统信息
        "device_info", "agent_status", "get_system_setting",
        "get_bluetooth_state", "get_device_location",
        "list_bluetooth_bonded_devices", "scan_bluetooth_devices",
        "get_speech_services_config", "get_all_workflows", "get_workflow",
        "get_terminal_session_screen", "read_environment_variable",

        // 音乐播放
        "music_play", "music_pause", "music_resume", "music_stop",
        "music_seek", "music_set_volume", "music_status", "music_play_queue",

        // 其他安全操作
        "calculate", "visit_web", "sleep", "toast",
        "start_app", "start_chat_service", "stop_chat_service",
        "create_new_chat", "switch_chat", "clear_active_character_card",
        "set_active_character_card", "use_package", "package_proxy",
        "trigger_tasker_event", "execute_intent",
        "export_character_card_to_tavern_json",
        "list_model_configs", "test_model_config_connection"
    )

    /** 检查工具是否属于高风险敏感工具 */
    fun isSensitive(toolName: String): Boolean {
        if (toolName in ALWAYS_SAFE_TOOLS) return false
        return toolName in HIGH_RISK_TOOLS
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
