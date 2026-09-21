package com.ai.assistance.operit.core.dualmode

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 模式管理器（§5.1）。
 *
 * 核心职责：
 * 1. 持有当前活跃模式（StateFlow，UI 层可观察）
 * 2. 提供互斥切换（Mutex 串行化，防并发切换导致状态混乱）
 * 3. 管理"双模式开关"（用户是否启用了双模式）
 * 4. 保存/恢复各模式的现场上下文
 * 5. ★ v1.0.1g 双模式角色卡隔离：代码模式固定绑定"代码编辑高手"
 */
class ModeManager private constructor(context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ModeManager? = null

        /** 代码模式默认角色卡ID */
        const val CODE_MODE_CHARACTER_ID = "code_mode_character"
        /** 代码模式默认角色名称 */
        const val CODE_MODE_CHARACTER_NAME = "代码编辑高手"
        /** 代码模式默认角色设定 */
        const val CODE_MODE_CHARACTER_SETTING =
            "You are an expert code editor and programming assistant. " +
            "Your focus is writing clean, efficient, and well-documented code. " +
            "You excel at debugging, refactoring, code review, and explaining complex programming concepts. " +
            "Always provide practical, runnable code examples. " +
            "When asked about code, think step by step and explain your reasoning."

        fun getInstance(context: Context): ModeManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ModeManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("operit_mode", Context.MODE_PRIVATE)

    private val _currentMode = MutableStateFlow(OperitMode.SINGLE)
    val currentMode: StateFlow<OperitMode> = _currentMode.asStateFlow()

    init {
        // Restore last saved mode from SharedPreferences
        val savedMode = prefs.getString("current_mode", null)
        _currentMode.value = when (savedMode) {
            OperitMode.CODE.name -> OperitMode.CODE
            OperitMode.ROLE.name -> OperitMode.ROLE
            else -> OperitMode.SINGLE
        }
    }

    /** 用户是否开启了双模式（默认开启） */
    val isDualModeEnabled: Boolean
        get() = prefs.getBoolean("dual_mode_enabled", true)

    /** 模式切换事件（供 UI 层订阅刷新） */
    private val _modeChangedEvent = MutableSharedFlow<OperitMode>(replay = 1)
    val modeChangedEvent: SharedFlow<OperitMode> = _modeChangedEvent.asSharedFlow()

    /** §5.1 互斥锁：并发切换串行化 */
    private val switchMutex = Mutex()

    /** 切换模式（suspend，调用方需处于协程环境） */
    suspend fun switchTo(mode: OperitMode) = switchMutex.withLock {
        if (!isDualModeEnabled && mode != OperitMode.SINGLE) {
            throw IllegalStateException("Dual mode not enabled")
        }
        val oldMode = _currentMode.value
        if (oldMode == mode) return@withLock

        // §5.1 顺序铁律：先保存旧模式现场，再切换
        saveModeState(oldMode)
        _currentMode.value = mode
        prefs.edit().putString("current_mode", mode.name).apply()
        loadModeState(mode)
        _modeChangedEvent.emit(mode)
    }

    /** 开启/关闭双模式 */
    fun setDualModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("dual_mode_enabled", enabled).apply()
        if (!enabled) {
            // 关闭双模式后恢复 SINGLE
            _currentMode.value = OperitMode.SINGLE
        }
    }

    /**
     * ★ v1.0.1g 获取指定模式绑定的角色卡ID。
     * - CODE 模式：固定返回代码编辑高手
     * - ROLE 模式：返回用户选择的角色卡（或 null 表示用全局设置）
     * - SINGLE 模式：返回 null（兼容旧版行为）
     */
    fun getModeCharacterCardId(mode: OperitMode): String? = when (mode) {
        OperitMode.CODE -> CODE_MODE_CHARACTER_ID
        OperitMode.ROLE -> prefs.getString("role_mode_character_id", null)
        OperitMode.SINGLE -> null
    }

    /**
     * ★ v1.0.1g 设置角色模式绑定的角色卡ID（代码模式不可设置，固定绑定）
     */
    fun setRoleModeCharacterCardId(cardId: String?) {
        prefs.edit().apply {
            if (cardId == null) remove("role_mode_character_id")
            else putString("role_mode_character_id", cardId)
        }.apply()
    }

    /** 保存旧模式上下文 */
    private fun saveModeState(mode: OperitMode) {
        prefs.edit().putString("current_mode", mode.name).apply()
    }

    /** 恢复目标模式上下文 */
    private fun loadModeState(mode: OperitMode) {
        // 由 DualModeStorageManager 实现具体读取
    }
}
