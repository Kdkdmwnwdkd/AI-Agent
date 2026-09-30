package com.ai.assistance.operit.core.tools.climode

import android.content.Context
import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.core.tools.PackageTool
import com.ai.assistance.operit.core.tools.PackageToolParameter
import com.ai.assistance.operit.core.tools.ToolPackage
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.mcp.MCPLocalServer
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
import com.ai.assistance.operit.data.skill.SkillRepository
import java.util.Locale
import org.json.JSONObject

enum class ToolExposureMode {
    FULL,
    CLI;

    companion object {
        fun resolve(providerType: ApiProviderType): ToolExposureMode {
            return when (providerType) {
                ApiProviderType.LMSTUDIO,
                ApiProviderType.OLLAMA,
                ApiProviderType.OPENAI_LOCAL,
                ApiProviderType.MNN,
                ApiProviderType.LLAMA_CPP -> CLI
                else -> FULL
            }
        }
    }
}

enum class HiddenToolSourceKind {
    BUILTIN,
    INTERNAL,
    PACKAGE,
    MCP,
    ACTIVATION;

    fun label(useEnglish: Boolean): String {
        return when (this) {
            BUILTIN -> "built-in"
            INTERNAL -> "internal"
            PACKAGE -> "package"
            MCP -> if (useEnglish) "mcp" else "MCP"
            ACTIVATION -> "activation"
        }
    }
}

data class HiddenToolCatalogEntry(
    val targetToolName: String,
    val displayName: String,
    val description: String,
    val parameterHints: List<String>,
    val sourceKind: HiddenToolSourceKind,
    val keywords: List<String> = emptyList(),
    val suggestedParamsJson: String? = null
)

object CliToolModeSupport {
    const val SEARCH_TOOL_NAME = "search"
    const val PROXY_TOOL_NAME = "proxy"
    const val PACKAGE_PROXY_TOOL_NAME = "package_proxy"
    private const val DEFAULT_SEARCH_LIMIT = 8
    private val PUBLIC_TOOL_NAMES = linkedSetOf(SEARCH_TOOL_NAME, PROXY_TOOL_NAME)
    private val RESERVED_PROXY_TARGETS =
        linkedSetOf(SEARCH_TOOL_NAME, PROXY_TOOL_NAME, PACKAGE_PROXY_TOOL_NAME)

    fun isCliPublicTool(toolName: String): Boolean {
        return PUBLIC_TOOL_NAMES.contains(toolName.trim())
    }

    /**
     * `search` 是两种模式共用的「目录检索」工具：
     * - CLI 模式：唯一入口，检索后必须走 `proxy` 调用；
     * - FULL 模式：只用于发现被收进目录的冷门工具，发现后**直接调用**即可，无需 proxy。
     *
     * 因此 `search` 在 FULL 模式下也要允许执行，而 `proxy` 仍然仅限 CLI。
     */
    fun isSearchToolAllowed(mode: ToolExposureMode, toolName: String): Boolean {
        val name = toolName.trim()
        if (name != SEARCH_TOOL_NAME) return false
        return mode == ToolExposureMode.CLI || mode == ToolExposureMode.FULL
    }

    /** `proxy` 仅 CLI 模式可用：FULL 模式的工具是直接暴露的，不需要代理层。 */
    fun isProxyToolAllowed(mode: ToolExposureMode, toolName: String): Boolean {
        return mode == ToolExposureMode.CLI && toolName.trim() == PROXY_TOOL_NAME
    }

    fun isReservedProxyTarget(toolName: String): Boolean {
        return RESERVED_PROXY_TARGETS.contains(toolName.trim())
    }

    fun defaultSearchLimit(): Int = DEFAULT_SEARCH_LIMIT

    /**
     * 目录检索工具 `search` 的单条定义（不含 CLI 模式的 `proxy`）。
     *
     * 两种模式共用同一个工具，但「检索到之后怎么用」不同，故描述需按模式区分：
     * - CLI 模式：检索后必须经 `proxy` 调用（调用方随后会追加 `proxy` 定义）；
     * - FULL 模式：检索后直接调用目标工具。
     */
    fun buildToolCatalogSearchPrompt(
        useEnglish: Boolean,
        mode: ToolExposureMode = ToolExposureMode.FULL
    ): List<ToolPrompt> {
        val viaProxy = mode == ToolExposureMode.CLI
        val description =
            if (useEnglish) {
                if (viaProxy) {
                    "Search the hidden tool catalog. Use this first to discover a hidden tool's " +
                        "name and parameters, then call `proxy` with that target tool name."
                } else {
                    "Search the built-in tool catalog for tools that are not currently listed. " +
                        "Use this when you need a capability you cannot find among the available tools, " +
                        "then call the discovered tool directly with its normal parameters."
                }
            } else {
                if (viaProxy) {
                    "检索隐藏工具目录。先用本工具查到目标工具名与参数，" +
                        "再用 `proxy` 携带该工具名调用。"
                } else {
                    "检索未直接列出的内置工具目录。" +
                        "当可用工具列表里找不到所需能力时，用本工具按关键词检索，" +
                        "拿到工具名后【直接调用】该工具即可，无需其它中转。"
                }
            }
        return listOf(
            ToolPrompt(
                name = SEARCH_TOOL_NAME,
                description = description,
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "query",
                        type = "string",
                        description = if (useEnglish) {
                            "capability keyword or tool name to search for, e.g. \"bluetooth\""
                        } else {
                            "能力关键词或工具名，例如「蓝牙」「截图」"
                        },
                        required = true
                    ),
                    ToolParameterSchema(
                        name = "limit",
                        type = "integer",
                        description = if (useEnglish) {
                            "optional, max results to return"
                        } else {
                            "可选，返回结果的最大条数"
                        },
                        required = false,
                        default = DEFAULT_SEARCH_LIMIT.toString()
                    )
                )
            )
        )
    }

    fun buildCliPublicToolPrompts(useEnglish: Boolean): List<ToolPrompt> {
        return buildToolCatalogSearchPrompt(useEnglish, ToolExposureMode.CLI) +
            listOf(buildCliProxyPrompt(useEnglish))
    }

    private fun buildCliProxyPrompt(useEnglish: Boolean): ToolPrompt {
        return ToolPrompt(
            name = PROXY_TOOL_NAME,
            description = "Execute a hidden tool after you discover its target tool name and parameter shape via search.",
            parametersStructured = listOf(
                ToolParameterSchema(
                    name = "tool_name",
                    type = "string",
                    description = "hidden target tool name, e.g. read_file or packageName:toolName",
                    required = true
                ),
                ToolParameterSchema(
                    name = "params",
                    type = "object",
                    description = "JSON params object forwarded to the hidden target tool",
                    required = true
                )
            )
        )
    }

    fun buildCliModePrompt(useEnglish: Boolean): String {
        val intro =
            if (useEnglish) {
                """
                CLI TOOL MODE
                - Only two public tools are available: `search` and `proxy`.
                - `search` only searches the hidden tool catalog. It does not read files, search code, or browse the web.
                - All real capabilities are hidden behind `proxy`.
                - Do not call hidden tools directly. Use `search` first, then call `proxy` with the discovered target tool name and JSON params.
                """.trimIndent()
            } else {
                """
                CLI TOOL MODE
                - Only two public tools are available: `search` and `proxy`.
                - `search` only searches the hidden tool catalog. It does not read files, search code, or browse the web.
                - All real capabilities are hidden behind `proxy`.
                - Do not call hidden tools directly. Use `search` first, then call `proxy` with the discovered target tool name and JSON params.
                """.trimIndent()
            }

        val category =
            SystemToolPromptCategory(
                categoryName = "Public tools",
                tools = buildCliPublicToolPrompts(useEnglish)
            ).toString()

        return "$intro\n\n$category"
    }

    suspend fun buildHiddenToolCatalog(
        context: Context,
        packageManager: PackageManager,
        roleCardToolAccess: ResolvedCharacterCardToolAccess,
        useEnglish: Boolean
    ): List<HiddenToolCatalogEntry> {
        val categories = buildBuiltinAndInternalCategories(useEnglish)
        val builtinToolNames = buildBuiltinToolNameSet(useEnglish)
        val entries = LinkedHashMap<String, HiddenToolCatalogEntry>()

        categories.forEach { category ->
            category.tools.forEach { tool ->
                if (tool.name == "use_package") {
                    return@forEach
                }
                if (isReservedProxyTarget(tool.name) || isCliPublicTool(tool.name)) {
                    return@forEach
                }
                if (!isToolNameAllowedForRoleCard(tool.name, null, roleCardToolAccess)) {
                    return@forEach
                }

                val sourceKind =
                    if (builtinToolNames.contains(tool.name)) {
                        HiddenToolSourceKind.BUILTIN
                    } else {
                        HiddenToolSourceKind.INTERNAL
                    }
                val parameterHints = buildParameterHints(tool)
                val entry =
                    HiddenToolCatalogEntry(
                        targetToolName = tool.name,
                        displayName = tool.name,
                        description = tool.description,
                        parameterHints = parameterHints,
                        sourceKind = sourceKind,
                        keywords = listOf(category.categoryName)
                    )
                entries.putIfAbsent("${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}", entry)
            }
        }

        val enabledPackages =
            packageManager.getEnabledPackageNames()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filter { !packageManager.isToolPkgContainer(it) }
                .filter { roleCardToolAccess.isExternalSourceAllowed(it) }
                .toList()

        enabledPackages.forEach { packageName ->
            val toolPackage = packageManager.getEffectivePackageTools(packageName) ?: return@forEach
            if (toolPackage.tools.isEmpty()) {
                addActivationEntry(
                    entries = entries,
                    displayName = packageName,
                    description = toolPackage.description.resolve(context),
                    keywordTag = "package",
                    sourceKind = HiddenToolSourceKind.ACTIVATION
                )
            } else {
                addPackageToolEntries(
                    entries = entries,
                    prefix = packageName,
                    toolPackage = toolPackage,
                    descriptionResolver = { it.description.resolve(context) },
                    paramHintResolver = { parameter ->
                        buildParameterHint(
                            name = parameter.name,
                            description = parameter.description.resolve(context),
                            type = parameter.type,
                            required = parameter.required
                        )
                    },
                    sourceKind = HiddenToolSourceKind.PACKAGE,
                    keywordTag = "package"
                )
            }
        }

        val skillPackages =
            SkillRepository.getInstance(context)
                .getAiVisibleSkillPackages()
                .filterKeys { roleCardToolAccess.isExternalSourceAllowed(it) }

        skillPackages.forEach { (skillName, skillPackage) ->
            addActivationEntry(
                entries = entries,
                displayName = skillName,
                description = skillPackage.description,
                keywordTag = "skill",
                sourceKind = HiddenToolSourceKind.ACTIVATION
            )
        }

        val mcpServers =
            packageManager.getAvailableServerPackages()
                .filterKeys { roleCardToolAccess.isExternalSourceAllowed(it) }
        val mcpLocalServer = MCPLocalServer.getInstance(context)

        mcpServers.forEach { (serverName, serverConfig) ->
            val cachedTools = mcpLocalServer.getCachedTools(serverName).orEmpty()
            if (cachedTools.isEmpty()) {
                addActivationEntry(
                    entries = entries,
                    displayName = serverName,
                    description = serverConfig.description,
                    keywordTag = "mcp",
                    sourceKind = HiddenToolSourceKind.ACTIVATION
                )
                return@forEach
            }

            addCachedMcpToolEntries(
                entries = entries,
                serverName = serverName,
                serverDescription = serverConfig.description,
                cachedTools = cachedTools
            )
        }

        return entries.values.toList()
    }

    fun searchHiddenToolCatalog(
        catalog: List<HiddenToolCatalogEntry>,
        query: String,
        limit: Int
    ): List<HiddenToolCatalogEntry> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return emptyList()
        }

        val terms = normalizedQuery.split(' ').filter { it.isNotBlank() }
        val ranked =
            catalog.mapNotNull { entry ->
                val score = scoreEntry(entry, normalizedQuery, terms)
                if (score <= 0) {
                    null
                } else {
                    score to entry
                }
            }

        return ranked
            .sortedWith(
                compareByDescending<Pair<Int, HiddenToolCatalogEntry>> { it.first }
                    .thenBy { it.second.targetToolName }
                    .thenBy { it.second.displayName }
            )
            .take(limit.coerceIn(1, 20))
            .map { it.second }
    }

    fun formatSearchResults(
        query: String,
        results: List<HiddenToolCatalogEntry>,
        useEnglish: Boolean
    ): String {
        if (results.isEmpty()) {
            return if (useEnglish) {
                "No hidden tools matched \"$query\". Try a broader capability keyword, then call proxy with a discovered target tool name."
            } else {
                "No hidden tools matched \"$query\". Try a broader capability keyword, then call proxy with a discovered target tool name."
            }
        }

        return buildString {
            if (useEnglish) {
                appendLine("Hidden tool search results for \"$query\":")
            } else {
                appendLine("Hidden tool search results for \"$query\":")
            }
            results.forEachIndexed { index, entry ->
                append(index + 1)
                append(". `")
                append(entry.displayName)
                append("` [")
                append(entry.sourceKind.label(useEnglish))
                appendLine("]")
                append("   ")
                appendLine(entry.description.ifBlank {
                    "No description."
                })
                append("   ")
                append("Target: `")
                append(entry.targetToolName)
                appendLine("`")
                if (!entry.suggestedParamsJson.isNullOrBlank()) {
                    append("   ")
                    append("Params hint: `")
                    append(entry.suggestedParamsJson)
                    appendLine("`")
                } else if (entry.parameterHints.isNotEmpty()) {
                    append("   ")
                    append("Params: ")
                    appendLine(entry.parameterHints.joinToString("; "))
                }
            }
        }.trimEnd()
    }

    fun buildCliTopLevelRestrictionErrorMessage(
        attemptedToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Tool '$attemptedToolName' is hidden in CLI tool mode. Use 'search' to find the hidden target tool, then call 'proxy'."
        } else {
            "Tool '$attemptedToolName' is hidden in CLI tool mode. Use 'search' to find the hidden target tool, then call 'proxy'."
        }
    }

    fun buildCliModeUnavailableMessage(useEnglish: Boolean): String {
        return if (useEnglish) {
            "This tool is only available in CLI tool mode."
        } else {
            "This tool is only available in CLI tool mode."
        }
    }

    fun buildProxyTargetUnavailableMessage(
        targetToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Hidden target tool '$targetToolName' is unavailable. Use 'search' first to discover a valid hidden tool name and params."
        } else {
            "Hidden target tool '$targetToolName' is unavailable. Use 'search' first to discover a valid hidden tool name and params."
        }
    }

    fun buildReservedProxyTargetMessage(
        targetToolName: String,
        useEnglish: Boolean
    ): String {
        return if (useEnglish) {
            "Hidden target tool '$targetToolName' is reserved and cannot be called through proxy."
        } else {
            "Hidden target tool '$targetToolName' is reserved and cannot be called through proxy."
        }
    }

    fun buildRoleAccessDeniedMessage(useEnglish: Boolean): String {
        return if (useEnglish) {
            "The current role card is not allowed to access this hidden tool."
        } else {
            "The current role card is not allowed to access this hidden tool."
        }
    }

    fun isToolNameAllowedForRoleCard(
        toolName: String,
        usePackageSourceName: String?,
        roleCardToolAccess: ResolvedCharacterCardToolAccess
    ): Boolean {
        return when {
            toolName == "use_package" -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("use_package")) {
                    false
                } else {
                    usePackageSourceName.isNullOrBlank() ||
                        roleCardToolAccess.isExternalSourceAllowed(usePackageSourceName)
                }
            }
            toolName.contains(':') -> {
                val sourceName = toolName.substringBefore(':').trim()
                sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
            }
            else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
        }
    }

    private fun buildBuiltinAndInternalCategories(useEnglish: Boolean): List<SystemToolPromptCategory> {
        return if (useEnglish) {
            SystemToolPrompts.getAllCategoriesEn()
        } else {
            SystemToolPrompts.getAllCategoriesCn()
        }
    }

    private fun buildBuiltinToolNameSet(useEnglish: Boolean): Set<String> {
        // 只取基础内置工具（不含内部工具），用于把工具归类为 BUILTIN 还是 INTERNAL。
        // 不可用 getAIAllCategories——它现在已包含内部工具，会导致全部被判成 BUILTIN。
        return SystemToolPrompts.getBaseBuiltinToolNameSet(useEnglish)
    }

    private fun buildParameterHints(tool: ToolPrompt): List<String> {
        val structured = tool.parametersStructured.orEmpty()
        if (structured.isNotEmpty()) {
            return structured.map { parameter ->
                buildParameterHint(
                    name = parameter.name,
                    description = parameter.description,
                    type = parameter.type,
                    required = parameter.required
                )
            }
        }
        return tool.parameters
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun buildParameterHint(
        name: String,
        description: String,
        type: String,
        required: Boolean
    ): String {
        val requiredText = if (required) "required" else "optional"
        return "$name [$type, $requiredText]: $description"
    }

    private fun addActivationEntry(
        entries: MutableMap<String, HiddenToolCatalogEntry>,
        displayName: String,
        description: String,
        keywordTag: String,
        sourceKind: HiddenToolSourceKind
    ) {
        val entry =
            HiddenToolCatalogEntry(
                targetToolName = "use_package",
                displayName = displayName,
                description = description,
                parameterHints = listOf("package_name [string, required]: $displayName"),
                sourceKind = sourceKind,
                keywords = listOf(keywordTag, "use_package", "activate"),
                suggestedParamsJson = "{\"package_name\":\"$displayName\"}"
            )
        entries.putIfAbsent("${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}", entry)
    }

    private fun addPackageToolEntries(
        entries: MutableMap<String, HiddenToolCatalogEntry>,
        prefix: String,
        toolPackage: ToolPackage,
        descriptionResolver: (PackageTool) -> String,
        paramHintResolver: (PackageToolParameter) -> String,
        sourceKind: HiddenToolSourceKind,
        keywordTag: String
    ) {
        toolPackage.tools
            .filter { !it.advice }
            .forEach { packageTool ->
                val targetToolName = "$prefix:${packageTool.name}"
                val entry =
                    HiddenToolCatalogEntry(
                        targetToolName = targetToolName,
                        displayName = targetToolName,
                        description = descriptionResolver(packageTool),
                        parameterHints = packageTool.parameters.map(paramHintResolver),
                        sourceKind = sourceKind,
                        keywords = listOf(prefix, keywordTag, toolPackage.name)
                    )
                entries.putIfAbsent("${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}", entry)
            }
    }

    private fun addCachedMcpToolEntries(
        entries: MutableMap<String, HiddenToolCatalogEntry>,
        serverName: String,
        serverDescription: String,
        cachedTools: List<MCPLocalServer.CachedToolInfo>
    ) {
        cachedTools.forEach { cachedTool ->
            val toolName = cachedTool.name.trim()
            if (toolName.isEmpty()) {
                return@forEach
            }
            val targetToolName = "$serverName:$toolName"
            val entry =
                HiddenToolCatalogEntry(
                    targetToolName = targetToolName,
                    displayName = targetToolName,
                    description = cachedTool.description.ifBlank { serverDescription },
                    parameterHints = buildCachedMcpParameterHints(cachedTool.inputSchema),
                    sourceKind = HiddenToolSourceKind.MCP,
                    keywords = listOf(serverName, "mcp", "cached")
                )
            entries.putIfAbsent("${entry.sourceKind}:${entry.targetToolName}:${entry.displayName}", entry)
        }
    }

    private fun buildCachedMcpParameterHints(inputSchemaJson: String): List<String> {
        val schema =
            runCatching { JSONObject(inputSchemaJson) }.getOrNull()
                ?: return emptyList()
        val properties = schema.optJSONObject("properties") ?: return emptyList()

        val requiredNames = linkedSetOf<String>()
        val requiredArray = schema.optJSONArray("required")
        if (requiredArray != null) {
            for (index in 0 until requiredArray.length()) {
                requiredArray.optString(index)
                    .takeIf { it.isNotBlank() }
                    ?.let(requiredNames::add)
            }
        }

        val parameterHints = mutableListOf<String>()
        val keys = properties.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val parameterObject = properties.optJSONObject(name)
            val type = parameterObject?.optString("type").takeUnless { it.isNullOrBlank() } ?: "string"
            val description = parameterObject?.optString("description").orEmpty()
            parameterHints +=
                buildParameterHint(
                    name = name,
                    description = description,
                    type = type,
                    required = requiredNames.contains(name)
                )
        }
        return parameterHints
    }

    private fun scoreEntry(
        entry: HiddenToolCatalogEntry,
        normalizedQuery: String,
        terms: List<String>
    ): Int {
        val displayName = normalize(entry.displayName)
        val targetName = normalize(entry.targetToolName)
        val description = normalize(entry.description)
        val params = normalize(entry.parameterHints.joinToString(" "))
        val keywords = normalize(entry.keywords.joinToString(" "))

        var score = 0
        if (displayName == normalizedQuery || targetName == normalizedQuery) {
            score += 300
        }
        if (displayName.startsWith(normalizedQuery) || targetName.startsWith(normalizedQuery)) {
            score += 140
        }
        if (displayName.contains(normalizedQuery) || targetName.contains(normalizedQuery)) {
            score += 100
        }
        if (description.contains(normalizedQuery) || keywords.contains(normalizedQuery)) {
            score += 40
        }
        if (params.contains(normalizedQuery)) {
            score += 25
        }

        var matchedTerms = 0
        terms.forEach { term ->
            var termMatched = false
            if (displayName.contains(term) || targetName.contains(term)) {
                score += 40
                termMatched = true
            }
            if (keywords.contains(term)) {
                score += 16
                termMatched = true
            }
            if (description.contains(term)) {
                score += 12
                termMatched = true
            }
            if (params.contains(term)) {
                score += 8
                termMatched = true
            }
            if (termMatched) {
                matchedTerms += 1
            }
        }
        if (matchedTerms == terms.size && terms.isNotEmpty()) {
            score += 30
        }

        return score
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}:_./-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
