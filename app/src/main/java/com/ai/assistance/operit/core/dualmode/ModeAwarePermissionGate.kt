package com.ai.assistance.operit.core.dualmode

import android.content.Context
import android.content.SharedPreferences

/**
 * 模式感知权限门（§6.5 授权历史按 scope 隔离）。
 *
 * 安全边界：绝不允许"代码模式批准了某路径后，角色模式自动获得授权"。
 * 授权键格式："{mode}:{permissionId}"，天然分桶。
 */
class ModeAwarePermissionGate(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("operit_permissions", Context.MODE_PRIVATE)

    /** 查询某模式下某权限是否已授权 */
    fun isGranted(mode: OperitMode, permissionId: String): Boolean {
        return prefs.getBoolean("${mode.name}:$permissionId", false)
    }

    /** 在该模式下授权 */
    fun grant(mode: OperitMode, permissionId: String) {
        prefs.edit().putBoolean("${mode.name}:$permissionId", true).apply()
    }

    /** 在该模式下显式拒绝（记录为 DENIED） */
    fun deny(mode: OperitMode, permissionId: String) {
        prefs.edit().putBoolean("${mode.name}:$permissionId", false).apply()
    }

    /** 仅清除该模式的全部授权（不影响其他模式） */
    fun revokeAllForMode(mode: OperitMode) {
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("${mode.name}:")) {
                editor.remove(key)
            }
        }
        editor.apply()
    }
}
