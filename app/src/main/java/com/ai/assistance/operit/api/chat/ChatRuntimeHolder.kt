package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.core.chat.hooks.ChatRuntimeHookContext
import com.ai.assistance.operit.core.chat.hooks.ChatRuntimeHookEvent
import com.ai.assistance.operit.core.chat.hooks.ChatRuntimeHookRegistry
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChatRuntimeHolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()
    private val _activeConversationCount = MutableStateFlow(0)
    val activeConversationCount: StateFlow<Int> = _activeConversationCount.asStateFlow()
    private val _currentSessionToolCount = MutableStateFlow(0)
    val currentSessionToolCount: StateFlow<Int> = _currentSessionToolCount.asStateFlow()

    init {
        ChatRuntimeSlot.values().forEach { slot ->
            getCore(slot)
        }
        setupCrossSessionSync()
        observeStats()
        observeRuntimeHooks()
    }

    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore {
        return cores.getOrPut(slot) {
            ChatServiceCore(
                context = appContext,
                coroutineScope = runtimeScope,
                selectionMode = when (slot) {
                    ChatRuntimeSlot.MAIN -> ChatSelectionMode.FOLLOW_GLOBAL
                    ChatRuntimeSlot.FLOATING -> ChatSelectionMode.LOCAL_ONLY
                    ChatRuntimeSlot.CODE_MODE -> ChatSelectionMode.FOLLOW_GLOBAL
                    ChatRuntimeSlot.ROLE_MODE -> ChatSelectionMode.FOLLOW_GLOBAL
                }
            )
        }
    }

    private fun observeStats() {
        val mainCore = getCore(ChatRuntimeSlot.MAIN)
        val floatingCore = getCore(ChatRuntimeSlot.FLOATING)

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                floatingCore.activeStreamingChatIds
            ) { mainActiveChatIds, floatingActiveChatIds ->
                (mainActiveChatIds + floatingActiveChatIds).size
            }.collect { count ->
                _activeConversationCount.value = count
            }
        }

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                mainCore.currentTurnToolInvocationCountByChatId,
                floatingCore.activeStreamingChatIds,
                floatingCore.currentTurnToolInvocationCountByChatId
            ) { mainActiveChatIds, mainCounts, floatingActiveChatIds, floatingCounts ->
                countCurrentTurnToolsForActiveChats(mainActiveChatIds, mainCounts) +
                    countCurrentTurnToolsForActiveChats(floatingActiveChatIds, floatingCounts)
            }.collect { count ->
                _currentSessionToolCount.value = count
            }
        }
    }

    private data class RuntimeHookObservation(
        val stateByChatId: Map<String, InputProcessingState>,
        val activeChatIds: Set<String>,
        val toolInvocationCountByChatId: Map<String, Int>
    )

    private fun observeRuntimeHooks() {
        ChatRuntimeSlot.values().forEach { slot ->
            val core = getCore(slot)
            val previousStates = mutableMapOf<String, InputProcessingState>()
            runtimeScope.launch {
                combine(
                    core.inputProcessingStateByChatId,
                    core.activeStreamingChatIds,
                    core.currentTurnToolInvocationCountByChatId
                ) { stateByChatId, activeChatIds, toolInvocationCountByChatId ->
                    RuntimeHookObservation(
                        stateByChatId = stateByChatId,
                        activeChatIds = activeChatIds,
                        toolInvocationCountByChatId = toolInvocationCountByChatId
                    )
                }.collect { observation ->
                    observation.stateByChatId.forEach { (chatId, state) ->
                        if (chatId == DEFAULT_CHAT_KEY) {
                            return@forEach
                        }
                        if (previousStates[chatId] == state) {
                            return@forEach
                        }
                        previousStates[chatId] = state
                        ChatRuntimeHookRegistry.dispatchAsync(
                            event = ChatRuntimeHookEvent.STATE_CHANGED,
                            context =
                                ChatRuntimeHookContext(
                                    context = appContext,
                                    chatId = chatId,
                                    slot = slot,
                                    state = state,
                                    activeChatIds = observation.activeChatIds,
                                    currentTurnToolInvocationCount =
                                        observation.toolInvocationCountByChatId[chatId] ?: 0,
                                    activeConversationCount = activeConversationCount.value,
                                    currentSessionToolCount = currentSessionToolCount.value
                                )
                        )
                    }

                    val activeStateChatIds = observation.stateByChatId.keys.toSet()
                    val iterator = previousStates.keys.iterator()
                    while (iterator.hasNext()) {
                        val chatId = iterator.next()
                        if (chatId !in activeStateChatIds) {
                            iterator.remove()
                        }
                    }
                }
            }
        }
    }

    private fun countCurrentTurnToolsForActiveChats(
        activeChatIds: Set<String>,
        countMap: Map<String, Int>
    ): Int {
        return activeChatIds.sumOf { chatId -> countMap[chatId] ?: 0 }
    }

    private fun setupCrossSessionSync() {
        // 同模式内的多窗口同步（MAIN ↔ FLOATING）
        registerChatSelectionSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.FLOATING,
            targetSlot = ChatRuntimeSlot.MAIN
        )

        // ★ v1.0.1g 双模式隔离：
        // CODE_MODE 和 ROLE_MODE 之间**不**互相同步（§6.2 物理隔离）。
        // 各自内部仍支持 MAIN ↔ FLOATING 多窗口同步。
        setupModeSlotSync()
    }

    /**
     * 设置模式插槽的同步策略（v1.0.1g）。
     *
     * - 代码模式：CODE_MODE ↔ FLOATING（当浮窗用于代码模式时）
     * - 角色模式：ROLE_MODE ↔ FLOATING（当浮窗用于角色模式时）
     * - 跨模式：CODE_MODE ↔ ROLE_MODE 之间**绝对不同步**
     */
    private fun setupModeSlotSync() {
        // 代码模式内同步：CODE_MODE ↔ FLOATING（方向与 MAIN ↔ FLOATING 相同）
        registerChatSelectionSync(
            sourceSlot = ChatRuntimeSlot.CODE_MODE,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.CODE_MODE,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.FLOATING,
            targetSlot = ChatRuntimeSlot.CODE_MODE
        )

        // 角色模式内同步：ROLE_MODE ↔ FLOATING
        registerChatSelectionSync(
            sourceSlot = ChatRuntimeSlot.ROLE_MODE,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.ROLE_MODE,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.FLOATING,
            targetSlot = ChatRuntimeSlot.ROLE_MODE
        )
    }

    private fun registerTurnSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)
        val targetCore = getCore(targetSlot)

        sourceCore.setAdditionalOnTurnComplete { chatId, inputTokens, outputTokens, windowSize ->
            if (chatId.isNullOrBlank()) {
                return@setAdditionalOnTurnComplete
            }
            if (targetCore.currentChatId.value != chatId) {
                return@setAdditionalOnTurnComplete
            }

            runtimeScope.launch {
                try {
                    targetCore.reloadChatMessagesSmart(chatId)
                    targetCore.getTokenStatisticsDelegate()
                        .setTokenCounts(chatId, inputTokens, outputTokens, windowSize)
                    AppLogger.d(
                        TAG,
                        "跨 Session smart 同步完成: $sourceSlot -> $targetSlot, chatId=$chatId, input=$inputTokens, output=$outputTokens, window=$windowSize"
                    )
                } catch (e: Exception) {
                    AppLogger.e(
                        TAG,
                        "跨 Session smart 同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                        e
                    )
                }
            }
        }
    }

    fun syncMainChatSelectionToFloating(chatId: String) {
        if (chatId.isBlank()) return
        syncChatSelection(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING,
            chatId = chatId
        )
    }

    private fun registerChatSelectionSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)

        runtimeScope.launch {
            sourceCore.currentChatId
                .collect { chatId ->
                    if (chatId.isNullOrBlank()) {
                        return@collect
                    }
                    syncChatSelection(sourceSlot, targetSlot, chatId)
                }
        }
    }

    private fun syncChatSelection(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot,
        chatId: String
    ) {
        val targetCore = getCore(targetSlot)
        if (targetCore.currentChatId.value == chatId) {
            return
        }

        try {
            targetCore.switchChatLocal(chatId)
            AppLogger.d(
                TAG,
                "跨 Session 当前聊天同步: $sourceSlot -> $targetSlot, chatId=$chatId"
            )
        } catch (e: Exception) {
            AppLogger.e(
                TAG,
                "跨 Session 当前聊天同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                e
            )
        }
    }

    companion object {
        private const val TAG = "ChatRuntimeHolder"
        private const val DEFAULT_CHAT_KEY = "__DEFAULT_CHAT__"

        @Volatile
        private var instance: ChatRuntimeHolder? = null

        fun getInstance(context: Context): ChatRuntimeHolder {
            return instance ?: synchronized(this) {
                instance ?: ChatRuntimeHolder(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
