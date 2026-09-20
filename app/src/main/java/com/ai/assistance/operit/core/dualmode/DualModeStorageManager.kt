package com.ai.assistance.operit.core.dualmode

import android.content.Context
import java.io.File

/**
 * 双模式存储管理器（§6.2 物理分区）。
 *
 * 为每种模式提供独立的文件系统目录，实现强隔离：
 * - SINGLE 模式：复用原有根目录（向后兼容）
 * - CODE / ROLE 模式：各自独立的子目录
 */
class DualModeStorageManager(private val context: Context) {

    /** 获取当前模式的专属目录 */
    fun getModeDir(mode: OperitMode): File {
        val base = context.filesDir
        return when (mode) {
            OperitMode.SINGLE -> base
            OperitMode.CODE -> File(base, "mode_code").apply { mkdirs() }
            OperitMode.ROLE -> File(base, "mode_role").apply { mkdirs() }
        }
    }

    fun getChatHistoryDir(mode: OperitMode): File =
        File(getModeDir(mode), "chat_history").apply { mkdirs() }

    fun getMemoryDir(mode: OperitMode): File =
        File(getModeDir(mode), "memory").apply { mkdirs() }

    fun getWorkspaceDir(mode: OperitMode): File =
        File(getModeDir(mode), "workspace").apply { mkdirs() }

    fun getJournalDir(mode: OperitMode): File =
        File(getModeDir(mode), ".journal").apply { mkdirs() }
}
