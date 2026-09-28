package com.ai.assistance.operit.data.model

import com.ai.assistance.operit.core.tools.ToolResultData
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/** Represents a tool parameter in an AI tool */
@Serializable data class ToolParameter(val name: String, val value: String)

/** Represents a tool that can be used by the AI */
@Serializable
data class AITool(
        val name: String,
        val parameters: List<ToolParameter> = emptyList(),
        val description: String = ""
)

/** Represents an invocation of a tool in the AI's response */
@Serializable
data class ToolInvocation(
        val tool: AITool,
        val rawText: String,
        @Contextual
        val responseLocation: IntRange // Where in the response this tool invocation was found
)

/** Represents the result of a tool execution */
@Serializable
data class ToolResult(
        val toolName: String,
        val success: Boolean,
        val result: ToolResultData,
        val error: String? = null,
        val traceId: String = "",
        /**
         * Whether retrying the same invocation could plausibly succeed.
         *
         * Only meaningful when [success] is false. Defaults to true so that existing constructions
         * keep meaning "no opinion" and the model retains its prior freedom to retry.
         *
         * Set to false for failures that are deterministic given the same arguments, so a retry
         * cannot possibly help: missing/invalid parameters, path not found, permission denied,
         * unsupported format, and similar. This is surfaced to the model as a `retryable` attribute
         * on the tool-result element, which stops whole categories of pointless retry loops.
         */
        val retryable: Boolean = true
)

/** Represents the validation result for tool parameters */
@Serializable data class ToolValidationResult(val valid: Boolean, val errorMessage: String = "")

