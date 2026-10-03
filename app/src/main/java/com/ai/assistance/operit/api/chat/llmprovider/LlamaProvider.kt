package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelOption
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LocalModelFileStore
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class LlamaProvider(
    private val context: Context,
    private val modelName: String,
    private val sessionConfig: LlamaSession.Config,
    private val providerType: ApiProviderType = ApiProviderType.LLAMA_CPP,
    private val enableToolCall: Boolean = false
) : AIService {

    /**
     * 本地模型文件校验的失败分类。
     *
     * 抽成独立的枚举 + 纯函数（[classifyModelFileProblem] / [resolveModelFile]）是为了：
     * 1. **可测**：纯 JVM 单元测试即可覆盖全部分支，不需要 Android Context / Robolectric；
     * 2. **单一真源**：UI 提示与推理前校验共用同一套判定，不会再出现两侧不一致。
     */
    enum class ModelFileProblem {
        /** 未填写模型名（本次闪退的直接原因） */
        NOT_SELECTED,
        /** 路径不存在 */
        NOT_FOUND,
        /** 路径指向目录而非文件（历史坑：空字符串会解析成工作目录且 exists() == true） */
        IS_DIRECTORY,
        /** 扩展名不是 .gguf */
        NOT_GGUF,
        /** 无读取权限 */
        NOT_READABLE,
    }

    companion object {
        private const val TAG = "LlamaProvider"
        /** 本地模型默认最大生成 token 数，避免无限制输出导致等待过久 */
        private const val DEFAULT_MAX_NEW_TOKENS = 1024

        /** 本地模型文件扩展名（llama.cpp 只认 GGUF） */
        const val MODEL_FILE_EXTENSION = ".gguf"

        fun getModelsDir(): File {
            return LocalModelFileStore.llamaModelsDir()
        }

        /**
         * 解析模型文件。
         *
         * 兼容两种写法：
         * - **绝对路径**：用户在设置里通过文件选择器指定的任意位置（如 `/storage/emulated/0/Models/xxx.gguf`）
         * - **相对文件名**：默认模型目录下的文件（历史行为，保持不变）
         *
         * 注意：**不做存在性判断**。`modelName` 为空时返回目录占位，
         * 仅用于展示路径；是否可用一律交给 [validateModelFile] 判断，
         * 绝不能把该结果直接喂给 native。
         */
        fun getModelFile(_context: Context, modelName: String): File {
            val trimmed = modelName.trim()
            if (trimmed.isEmpty()) return File(getModelsDir(), trimmed)
            val candidate = File(trimmed)
            return if (candidate.isAbsolute) candidate else File(getModelsDir(), trimmed)
        }

        /**
         * 纯逻辑：把一个已解析的候选 [File] 归类成失败原因；通过校验返回 null。
         *
         * 不依赖 Android，可直接单测。
         */
        fun classifyModelFileProblem(file: File, declaredPath: String): ModelFileProblem? {
            if (declaredPath.isBlank()) return ModelFileProblem.NOT_SELECTED
            if (!file.exists()) return ModelFileProblem.NOT_FOUND
            // 顺序很关键：目录必须先于扩展名判断。
            // 目录名通常不带 .gguf，但历史实现正是因为漏了这一步，把目录喂给了 native。
            if (!file.isFile) return ModelFileProblem.IS_DIRECTORY
            if (!file.name.lowercase().endsWith(MODEL_FILE_EXTENSION)) {
                return ModelFileProblem.NOT_GGUF
            }
            if (!file.canRead()) return ModelFileProblem.NOT_READABLE
            return null
        }

        /**
         * 纯逻辑：解析 + 校验。校验通过返回 [File]，失败返回 null。
         *
         * @param modelsDir 相对文件名时的基准目录；传 null 表示只接受绝对路径（便于单测）。
         */
        fun resolveModelFile(modelName: String, modelsDir: File?): File? {
            val trimmed = modelName.trim()
            if (trimmed.isEmpty()) return null
            val candidate = File(trimmed)
            val file =
                if (candidate.isAbsolute) candidate
                else modelsDir?.let { File(it, trimmed) } ?: return null
            return if (classifyModelFileProblem(file, trimmed) == null) file else null
        }

        /**
         * 模型文件严格校验（**落地前最后一道闸**）。
         *
         * 背景：历史实现只判断 `File.exists()`。Android 上 `File("")` 的规范路径就是当前工作目录
         * （即 `models/llama` 文件夹）且 `exists()` 为 true，于是"模型名称栏留空"会把**目录路径**
         * 一路喂到 `llama_model_load_from_file()`，GGUF 解析直接失败并在 native 层崩溃/闪退。
         *
         * @return 校验通过返回 [File]；失败返回 null，调用方用 [describeModelFileProblem] 取文案。
         */
        fun validateModelFile(modelName: String): File? {
            return resolveModelFile(modelName, getModelsDir()).also {
                if (it == null) {
                    AppLogger.w(TAG, "模型文件校验未通过：${modelName.trim()}")
                }
            }
        }

        /** 与 [classifyModelFileProblem] 的分支一一对应，返回用户可见的失败原因。 */
        fun describeModelFileProblem(context: Context, modelName: String): String {
            val trimmed = modelName.trim()
            val file = getModelFile(context, trimmed)
            val path = file.absolutePath
            return when (classifyModelFileProblem(file, trimmed)) {
                ModelFileProblem.NOT_SELECTED ->
                    context.getString(R.string.llama_error_model_not_selected)
                ModelFileProblem.NOT_FOUND ->
                    context.getString(R.string.llama_error_model_file_not_exist, path)
                ModelFileProblem.IS_DIRECTORY ->
                    context.getString(R.string.llama_error_model_path_is_directory, path)
                ModelFileProblem.NOT_GGUF ->
                    context.getString(R.string.llama_error_model_not_gguf, path)
                ModelFileProblem.NOT_READABLE ->
                    context.getString(R.string.llama_error_model_not_readable, path)
                null -> ""
            }
        }
    }

    private var _inputTokenCount: Long = 0L
    private var _outputTokenCount: Long = 0L
    private var _cachedInputTokenCount: Long = 0L

    @Volatile
    private var isCancelled = false

    private val sessionLock = Any()
    private var session: LlamaSession? = null

    override val inputTokenCount: Long
        get() = _inputTokenCount

    override val cachedInputTokenCount: Long
        get() = _cachedInputTokenCount

    override val outputTokenCount: Long
        get() = _outputTokenCount

    override val providerModel: String
        get() = "${providerType.name}:$modelName"

    override fun resetTokenCounts() {
        _inputTokenCount = 0L
        _outputTokenCount = 0L
        _cachedInputTokenCount = 0L
    }

    private fun logLargeString(prefix: String, message: String) {
        val maxLogSize = 3000
        if (message.length <= maxLogSize) {
            AppLogger.d(TAG, "$prefix$message")
            return
        }

        val chunkCount = (message.length + maxLogSize - 1) / maxLogSize
        for (index in 0 until chunkCount) {
            val start = index * maxLogSize
            val end = minOf((index + 1) * maxLogSize, message.length)
            val chunk = message.substring(start, end)
            AppLogger.d(TAG, "$prefix Part ${index + 1}/$chunkCount: $chunk")
        }
    }

    private fun logFinalOutput(content: CharSequence, prefix: String = "Final llama.cpp output: ") {
        val finalOutput = content.toString()
        if (finalOutput.isBlank()) {
            AppLogger.d(TAG, "${prefix.trimEnd()}[empty]")
            return
        }
        logLargeString(prefix, finalOutput)
    }

    override fun cancelStreaming() {
        isCancelled = true
        synchronized(sessionLock) {
            session?.cancel()
        }
    }

    override fun release() {
        synchronized(sessionLock) {
            session?.release()
            session = null
        }
    }

    override suspend fun getModelsList(context: Context): Result<List<ModelOption>> {
        return ModelListFetcher.getLlamaLocalModels(context)
    }

    override suspend fun testConnection(context: Context): Result<String> = withContext(Dispatchers.IO) {
        if (!LlamaSession.isAvailable()) {
            return@withContext Result.failure(Exception(LlamaSession.getUnavailableReason()))
        }

        val modelFile = validateModelFile(modelName)
            ?: return@withContext Result.failure(Exception(describeModelFileProblem(context, modelName)))

        val testSession = LlamaSession.create(
            pathModel = modelFile.absolutePath,
            config = sessionConfig
        ) ?: return@withContext Result.failure(Exception(context.getString(R.string.llama_error_create_session_failed)))

        testSession.release()
        Result.success("llama.cpp backend is available (native ready).")
    }

    override suspend fun calculateInputTokens(
        chatHistory: List<PromptTurn>,
        availableTools: List<ToolPrompt>?
    ): Long {
        return withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val s = ensureSessionLocked()
                if (s == null) return@runCatching null

                val prompt = if (shouldUseToolCall(availableTools)) {
                    val messagesJson = StructuredToolCallBridge.buildMessagesJson(
                        history = chatHistory,
                        preserveThinkInHistory = false
                    )
                    val toolsJson = StructuredToolCallBridge.buildToolsJson(availableTools)
                    s.applyStructuredChatTemplate(messagesJson, toolsJson, true, true)
                } else {
                    val (roles, contents) = buildPlainPromptMessages(
                        chatHistory = chatHistory,
                        preserveThinkInHistory = false
                    )
                    s.applyChatTemplate(roles, contents, true, true)
                } ?: return@runCatching null

                s.countTokens(prompt).toLong()
            }.getOrNull() ?: 0L
        }
    }

    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Long, cachedInput: Long, output: Long) -> Unit,
        onUsageReported: (suspend (com.ai.assistance.operit.data.stats.ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        recordTokenUsage: Boolean,
        onUsageFinalized: (suspend (attempt: Int?) -> Unit)?,
    ): Stream<String> = stream {
        isCancelled = false

        if (!LlamaSession.isAvailable()) {
            throw IOException("${context.getString(R.string.llama_error_prefix)}: ${LlamaSession.getUnavailableReason()}")
        }

        val modelFile = validateModelFile(modelName)
            ?: throw IOException("${context.getString(R.string.llama_error_prefix)}: ${describeModelFileProblem(context, modelName)}")

        val s = withContext(Dispatchers.IO) {
            ensureSessionLocked()
        }
        if (s == null) {
            throw IOException(context.getString(R.string.llama_error_session_create_failed))
        }

        val effectiveEnableToolCall = shouldUseToolCall(availableTools)

        if (effectiveEnableToolCall) {
            AppLogger.d(TAG, "llama.cpp Tool Call转换已启用，tools=${availableTools?.size ?: 0}")
        }

        val prompt = withContext(Dispatchers.IO) {
            if (effectiveEnableToolCall) {
                val messagesJson = StructuredToolCallBridge.buildMessagesJson(
                    history = chatHistory,
                    preserveThinkInHistory = preserveThinkInHistory
                )
                val toolsJson = StructuredToolCallBridge.buildToolsJson(availableTools)
                s.applyStructuredChatTemplate(messagesJson, toolsJson, enableThinking, true)
            } else {
                val (roles, contents) = buildPlainPromptMessages(
                    chatHistory = chatHistory,
                    preserveThinkInHistory = preserveThinkInHistory
                )
                s.applyChatTemplate(roles, contents, enableThinking, true)
            }
        }
        if (prompt.isNullOrBlank()) {
            throw IOException(context.getString(R.string.llama_error_chat_template_failed))
        }

        logLargeString("Final prompt before llama generation: ", prompt)

        val temperature = modelParameters
            .firstOrNull { it.id == "temperature" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val topP = modelParameters
            .firstOrNull { it.id == "top_p" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val topK = modelParameters
            .firstOrNull { it.id == "top_k" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toInt() }
            ?: 0
        val repetitionPenalty = modelParameters
            .firstOrNull { it.id == "repetition_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 1.0f
        val frequencyPenalty = modelParameters
            .firstOrNull { it.id == "frequency_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 0.0f
        val presencePenalty = modelParameters
            .firstOrNull { it.id == "presence_penalty" && it.isEnabled }
            ?.let { (it.currentValue as? Number)?.toFloat() }
            ?: 0.0f

        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                s.setSamplingParams(
                    temperature = temperature,
                    topP = topP,
                    topK = topK,
                    repetitionPenalty = repetitionPenalty,
                    frequencyPenalty = frequencyPenalty,
                    presencePenalty = presencePenalty,
                    penaltyLastN = 64
                )
            }

            if (!effectiveEnableToolCall) {
                kotlin.runCatching {
                    s.clearToolCallGrammar()
                    Unit
                }.onFailure {
                    AppLogger.w(TAG, "清理llama.cpp原生Tool Call状态失败", it)
                }
            }
        }

        _inputTokenCount = kotlin.runCatching { s.countTokens(prompt).toLong() }.getOrElse { 0L }
        _outputTokenCount = 0L
        onTokensUpdated(_inputTokenCount, 0L, 0L)

        val requestedMaxNewTokens = modelParameters
            .find { it.name == "max_tokens" }
            ?.let { (it.currentValue as? Number)?.toInt() }
            ?.takeIf { it > 0 }
        // 本地模型必须限制生成长度，否则可能生成上千 token 导致等待过久
        val maxNewTokens = requestedMaxNewTokens ?: DEFAULT_MAX_NEW_TOKENS

        AppLogger.d(
            TAG,
            "开始llama.cpp推理，history=${chatHistory.size}, threads=${sessionConfig.nThreads}, n_ctx=${sessionConfig.nCtx}, n_batch=${sessionConfig.nBatch}, n_ubatch=${sessionConfig.nUBatch}, gpu_layers=${sessionConfig.nGpuLayers}, mmap=${sessionConfig.useMmap}"
        )

        var outputTokenCount = 0L
        val toolCallOutputBuffer = StringBuilder()
        val finalOutputBuffer = StringBuilder()

        val usageReporter = LocalUsageReporter(
            com.ai.assistance.operit.data.stats.ProviderUsageNormalizer.SOURCE_LLAMA,
            onUsageReported,
        )
        val success = withContext(Dispatchers.IO) {
                s.generateStream(prompt, maxNewTokens) { token ->
                    if (isCancelled) {
                        false
                    } else {
                        outputTokenCount += 1L
                        _outputTokenCount = outputTokenCount

                        if (effectiveEnableToolCall) {
                            toolCallOutputBuffer.append(token)
                        } else {
                            finalOutputBuffer.append(token)
                            runBlocking { emit(token) }
                        }

                        kotlin.runCatching {
                            kotlinx.coroutines.runBlocking {
                                onTokensUpdated(_inputTokenCount, 0L, _outputTokenCount)
                            }
                        }

                        true
                    }
                }
            }

        LocalGenerationEnd.end(
            cancelled = isCancelled,
            success = success,
            usageReporter = usageReporter,
            inputTokens = _inputTokenCount,
            outputTokens = _outputTokenCount,
            cancelMessage = context.getString(R.string.llama_error_request_cancelled),
            emitToolResult = {
                if (effectiveEnableToolCall) {
                    val normalizedPayload = withContext(Dispatchers.IO) {
                        kotlin.runCatching {
                            s.parseToolCallResponse(toolCallOutputBuffer.toString())
                        }.getOrNull()
                    }
                    val converted = StructuredToolCallBridge.convertToolCallPayloadToXml(
                        normalizedPayload ?: toolCallOutputBuffer.toString()
                    )
                    if (converted.isNotBlank()) {
                        finalOutputBuffer.append(converted)
                        emit(converted)
                    }
                }
            },
            failWith = {
                kotlin.runCatching {
                    onNonFatalError(context.getString(R.string.llama_error_inference_failed))
                }
                throw IOException(context.getString(R.string.llama_error_inference_failed))
            },
        )
        onUsageFinalized?.invoke(1)

        AppLogger.i(TAG, "llama.cpp推理完成，输出token数: $_outputTokenCount")
        logFinalOutput(finalOutputBuffer, "Final llama.cpp output summary: ")
    }

    private fun shouldUseToolCall(availableTools: List<ToolPrompt>?): Boolean {
        return enableToolCall && !availableTools.isNullOrEmpty()
    }

    private fun buildPlainPromptMessages(
        chatHistory: List<PromptTurn>,
        preserveThinkInHistory: Boolean
    ): Pair<List<String>, List<String>> {
        val normalizedHistory =
            chatHistory.map { turn ->
                val role =
                    when (turn.kind) {
                        PromptTurnKind.SYSTEM -> "system"
                        PromptTurnKind.USER,
                        PromptTurnKind.SUMMARY,
                        PromptTurnKind.TOOL_RESULT -> "user"
                        PromptTurnKind.ASSISTANT,
                        PromptTurnKind.TOOL_CALL -> "assistant"
                    }
                val rawContent =
                    if (!preserveThinkInHistory && turn.kind == PromptTurnKind.ASSISTANT) {
                        ChatUtils.removeThinkingContent(turn.content)
                    } else {
                        turn.content
                    }
                val content = ChatUtils.stripOpenAiResponsesProtocolMarkup(rawContent)
                role to content
            }
        val roles = ArrayList<String>(normalizedHistory.size)
        val contents = ArrayList<String>(normalizedHistory.size)

        for ((role, content) in normalizedHistory) {
            roles.add(role)
            contents.add(content)
        }

        return roles to contents
    }

    private fun ensureSessionLocked(): LlamaSession? {
        synchronized(sessionLock) {
            session?.let { return it }
            // 二次校验：绝不把目录/不存在/非 gguf 的路径传给 native，
            // 否则 llama_model_load_from_file 会在 native 层失败并导致闪退。
            val modelFile = validateModelFile(modelName)
            if (modelFile == null) {
                AppLogger.w(TAG, "拒绝创建 llama 会话：${describeModelFileProblem(context, modelName)}")
                return null
            }
            val created = LlamaSession.create(
                pathModel = modelFile.absolutePath,
                config = sessionConfig
            )
            session = created
            return created
        }
    }

}
