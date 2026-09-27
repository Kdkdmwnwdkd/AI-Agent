package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.util.AppLogger

/**
 * Phase 1: ModelRouter - Routes AI requests between cloud and on-device models.
 *
 * Inserted between EnhancedAIService and AIServiceFactory.
 * Decisions based on: task type, tool requirements, context size, network state.
 *
 * 本地模型配置通过 SharedPreferences 存储，键前缀 "local_model_"。
 * 设置路径：设置页 → 模型管理 → 本地模型配置（UI 待实现）。
 */
class ModelRouter private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ModelRouter"
        private const val SIMPLE_CHAT_THRESHOLD = 2048
        private const val PREFS_NAME = "model_router_config"

        @Volatile
        private var instance: ModelRouter? = null

        fun getInstance(context: Context): ModelRouter {
            return instance ?: synchronized(this) {
                instance ?: ModelRouter(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class RoutingDecision(
        val target: ModelTarget,
        val configId: String?,
        val reason: String
    )

    enum class ModelTarget {
        CLOUD,
        LOCAL_MNN,
        LOCAL_LLAMA
    }

    /**
     * Decide target model for a request.
     *
     * @param localConfigId 自动发现的本地 llama 模型配置 ID（由调用方扫描配置列表获得）。
     *                      若为 null 则回退到 SharedPreferences 中的手动配置。
     * @return Decision with target and optional configId override.
     */
    fun route(
        functionType: FunctionType,
        requiresToolCalling: Boolean = false,
        estimatedContextSize: Int = 0,
        networkAvailable: Boolean = true,
        localConfigId: String? = null
    ): RoutingDecision {
        if (requiresToolCalling) {
            log(functionType, ModelTarget.CLOUD, "tool calling not supported locally")
            return RoutingDecision(ModelTarget.CLOUD, null, "Tool calling requires cloud")
        }
        if (!networkAvailable) {
            val local = localConfig(functionType, localConfigId)
            return if (local != null) {
                log(functionType, local.target, "network offline -> local")
                RoutingDecision(local.target, local.configId, "Network unavailable, using local model")
            } else {
                log(functionType, ModelTarget.CLOUD, "no network, no local model")
                RoutingDecision(ModelTarget.CLOUD, null, "No network and no local model configured")
            }
        }
        val limit = localContextLimit(functionType, localConfigId)
        if (limit > 0 && estimatedContextSize > limit) {
            log(functionType, ModelTarget.CLOUD, "context $estimatedContextSize > limit $limit")
            return RoutingDecision(ModelTarget.CLOUD, null, "Context exceeds local capacity")
        }
        if (functionType == FunctionType.CHAT && !requiresToolCalling) {
            val local = localConfig(functionType, localConfigId)
            if (local != null) {
                log(functionType, local.target, "chat -> local")
                return RoutingDecision(local.target, local.configId, "Chat routed to local model")
            }
        }
        log(functionType, ModelTarget.CLOUD, "default")
        return RoutingDecision(ModelTarget.CLOUD, null, "Default routing to cloud")
    }

    fun isLocalModelAvailable(functionType: FunctionType, localConfigId: String? = null): Boolean =
        localConfig(functionType, localConfigId) != null

    /**
     * 配置本地模型。设置后 route() 会根据条件路由到本地。
     * @param functionType 要配置的功能类型
     * @param enabled 是否启用本地模型
     * @param target 本地模型类型 (MNN / LLAMA)
     * @param configId 本地模型配置 ID（对应 MultiServiceManager 中的配置）
     * @param contextLimit 本地模型最大上下文长度
     */
    fun setLocalModelConfig(
        functionType: FunctionType,
        enabled: Boolean,
        target: ModelTarget,
        configId: String,
        contextLimit: Int = 2048
    ) {
        prefs.edit()
            .putBoolean("local_enabled_${functionType.name}", enabled)
            .putString("local_target_${functionType.name}", if (target == ModelTarget.LOCAL_LLAMA) "LLAMA" else "MNN")
            .putString("local_config_id_${functionType.name}", configId)
            .putInt("local_context_limit_${functionType.name}", contextLimit)
            .apply()
        AppLogger.i(TAG, "Local model config updated: $functionType -> $target (enabled=$enabled)")
    }

    /**
     * 清除指定功能的本地模型配置。
     */
    fun clearLocalModelConfig(functionType: FunctionType) {
        prefs.edit()
            .remove("local_enabled_${functionType.name}")
            .remove("local_target_${functionType.name}")
            .remove("local_config_id_${functionType.name}")
            .remove("local_context_limit_${functionType.name}")
            .apply()
        AppLogger.i(TAG, "Local model config cleared for $functionType")
    }

    private data class LocalConfig(val target: ModelTarget, val configId: String, val contextLimit: Int)

    /**
     * 解析本地模型配置。
     * 优先使用调用方传入的 localConfigId（自动发现的 llama 配置），
     * 其次回退到 SharedPreferences 中的手动配置。
     */
    private fun localConfig(functionType: FunctionType, localConfigId: String?): LocalConfig? {
        // 自动发现的本地 llama 配置
        if (!localConfigId.isNullOrBlank()) {
            val contextLimit = prefs.getInt("local_context_limit_${functionType.name}", 2048)
            return LocalConfig(ModelTarget.LOCAL_LLAMA, localConfigId, contextLimit)
        }

        // 手动配置（SharedPreferences）
        val enabled = prefs.getBoolean("local_enabled_${functionType.name}", false)
        if (!enabled) return null

        val configId = prefs.getString("local_config_id_${functionType.name}", null)
        if (configId.isNullOrBlank()) return null

        val targetStr = prefs.getString("local_target_${functionType.name}", "MNN")
        val target = if (targetStr == "LLAMA") ModelTarget.LOCAL_LLAMA else ModelTarget.LOCAL_MNN
        val contextLimit = prefs.getInt("local_context_limit_${functionType.name}", 2048)

        return LocalConfig(target, configId, contextLimit)
    }

    private fun localContextLimit(functionType: FunctionType, localConfigId: String?): Int =
        localConfig(functionType, localConfigId)?.contextLimit ?: 0

    private fun log(functionType: FunctionType, target: ModelTarget, reason: String) {
        AppLogger.d(TAG, "[route] $functionType -> $target | $reason")
    }
}
