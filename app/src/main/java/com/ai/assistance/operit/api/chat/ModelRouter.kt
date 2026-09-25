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
 * Current: Skeleton with rule-based routing (no behavior change yet).
 * Future: ML-based routing, dynamic capability negotiation, token cost optimization.
 */
class ModelRouter private constructor(context: Context) {

    companion object {
        private const val TAG = "ModelRouter"
        private const val SIMPLE_CHAT_THRESHOLD = 2048

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
     * @return Decision; if local chosen but unavailable, caller must fallback.
     */
    fun route(
        functionType: FunctionType,
        requiresToolCalling: Boolean = false,
        estimatedContextSize: Int = 0,
        networkAvailable: Boolean = true
    ): RoutingDecision {
        if (requiresToolCalling) {
            log(functionType, ModelTarget.CLOUD, "tool calling not supported locally")
            return RoutingDecision(ModelTarget.CLOUD, null, "Tool calling requires cloud")
        }
        if (!networkAvailable) {
            val local = localConfig(functionType)
            return if (local != null) {
                log(functionType, local.target, "network offline -> local")
                RoutingDecision(local.target, local.configId, "Network unavailable")
            } else {
                log(functionType, ModelTarget.CLOUD, "no network, no local model")
                RoutingDecision(ModelTarget.CLOUD, null, "No network and no local model")
            }
        }
        val limit = localContextLimit(functionType)
        if (limit > 0 && estimatedContextSize > limit) {
            log(functionType, ModelTarget.CLOUD, "context $estimatedContextSize > limit $limit")
            return RoutingDecision(ModelTarget.CLOUD, null, "Context exceeds local capacity")
        }
        if (functionType == FunctionType.CHAT && estimatedContextSize < SIMPLE_CHAT_THRESHOLD && !requiresToolCalling) {
            val local = localConfig(functionType)
            if (local != null) {
                log(functionType, local.target, "simple chat -> local")
                return RoutingDecision(local.target, local.configId, "Simple chat routed locally")
            }
        }
        log(functionType, ModelTarget.CLOUD, "default")
        return RoutingDecision(ModelTarget.CLOUD, null, "Default routing to cloud")
    }

    fun isLocalModelAvailable(functionType: FunctionType): Boolean = localConfig(functionType) != null

    private data class LocalConfig(val target: ModelTarget, val configId: String, val contextLimit: Int)

    private fun localConfig(functionType: FunctionType): LocalConfig? {
        // Phase 1 skeleton: always returns null (local models not yet configured)
        // Phase 1b: read from ModelConfigManager / preferences
        return null
    }

    private fun localContextLimit(functionType: FunctionType): Int = localConfig(functionType)?.contextLimit ?: 0

    private fun log(functionType: FunctionType, target: ModelTarget, reason: String) {
        AppLogger.d(TAG, "[route] $functionType -> $target | $reason")
    }
}