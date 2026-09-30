# 角色卡工具白名单重构方案

> 起因：真机验收中发现，角色卡开启「自定义允许使用的工具」后，AI 完全无法调用 `music_play` 等内置工具。
> 用户实测结论：「你自己看看有吗？都没有好吗？」——白名单页压根不显示这些工具。
> 代码核查后确认：这不是用户漏勾，而是**双层 bug**。

---

## 一、现象与证据

真机录屏 `15636.mp4`（帧 007 / 009 / 011 / 016）显示：

| 位置 | 内容 |
| --- | --- |
| 角色卡编辑 → 自定义工具开关 | 计数条：**内置工具 20 · 包 18 · Skill 1 · MCP 0** |
| 点入白名单弹窗 | 四个 Tab：**内建工具 / 包 / Skill / MCP** |
| 内建工具列表 | `grep_context`、`run_code`、`file_tree`、`device info`、`time`、`various_search`、`workflow` —— 全为基础类 |
| MCP Tab | 「当前没有可用的 MCP 服务器」 |

**整个列表里不存在任何音乐工具，也不存在任何内部工具。**

---

## 二、根因：双层 bug

### 第一层（表层）——UI 不渲染内部工具

`app/src/main/java/com/ai/assistance/operit/core/config/SystemToolPrompts.kt:751`

```kotlin
fun getManageableToolPrompts(
    useEnglish: Boolean,
    toolOrder: List<String> = emptyList()
): List<ManageableToolPrompt> {
    val baseCategories = if (useEnglish) {
        listOf(basicTools, fileSystemTools, httpTools, memoryTools)
    } else {
        listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn)
    }
    ...
}
```

只有 4 个分类，实测合计 **17 个工具**（与 UI 上的「20」同量级，差异来自 `http_request` 等重名项与展示口径）：

| 分类 | 数量 | 工具 |
| --- | --- | --- |
| `basicTools`（可用工具） | 2 | `sleep`, `use_package` |
| `fileSystemTools`（文件系统工具） | 10 | `list_files`, `read_file`, `read_file_part`, `create_file`, `edit_file`, `delete_file`, `make_directory`, `find_files`, `grep_code`, `grep_context` |
| `httpTools`（HTTP工具） | 3 | `visit_web`, `web_search`, `http_request` |
| `memoryTools`（记忆与记忆库工具） | 2 | `query_memory`, `get_memory_by_title` |

而全部内部工具定义在 `SystemToolPromptsInternal.kt`，共 **146 个**，分布在 12 个分类中：

| 分类（中文名） | 数量 | 代表工具 |
| --- | --- | --- |
| 内部工具 | 42 | `execute_shell`, `apply_file`, `create_terminal_session`, **`music_play`**, `music_play_queue`, ... |
| 拓展记忆工具 | 6 | `create_memory`, `update_user_profile`, ... |
| 拓展 HTTP 工具 | 3 | `multipart_request`, `manage_cookies` |
| 拓展文件工具 | 8 | `zip_files`, `open_file`, `share_file`, ... |
| Tasker 工具 | 1 | `trigger_tasker_event` |
| 工作流工具 | 9 | `create_workflow`, `trigger_workflow`, ... |
| 对话工具 | 13 | `start_chat_service`, `send_message_to_ai`, ... |
| 内部文件工具 | 4 | `read_file_full`, `write_file_binary`, ... |
| 内部 UI 工具 | 9 | `get_page_info`, `tap`, `capture_screenshot`, ... |
| 软件设置工具 | 16 | `list_model_configs`, `set_speech_services_config`, ... |
| 内部系统工具 | 32 | `install_app`, `send_notification`, `get_device_location`, ... |
| FFmpeg 工具 | 3 | `ffmpeg_execute`, `ffmpeg_convert`, ... |

`getManageableToolPrompts()` 一个都没纳入 → **白名单页结构性缺失这 146 项**。

调用链：
```
CharacterCardDialog.kt:180   builtinToolOptions = getManageableToolPrompts(useEnglish).map { ... }
        ↓
CharacterCardDialog.kt:991   CharacterCardToolAccessDialog(builtinOptions = builtinToolOptions, ...)
        ↓
CharacterCardDialog.kt:1090  val currentOptions = when (selectedTabIndex) { 0 -> builtinOptions ... }
```

### 第二层（底层，更致命）——即使勾上也会被静默剔除

`app/src/main/java/com/ai/assistance/operit/data/preferences/CharacterCardToolAccessResolver.kt:91-97`

```kotlin
val allowedBuiltinTools = LinkedHashSet(roleCardConfig.allowedBuiltinTools)
val manageableBuiltinNames = SystemToolPrompts
    .getManageableToolPrompts(useEnglish = false)      // ← 仍然是那 17 个
    .mapTo(LinkedHashSet()) { it.name }
val effectiveBuiltinToolVisibility = manageableBuiltinNames.associateWith { toolName ->
    (effectiveGlobalToolVisibility[toolName] ?: true) && allowedBuiltinTools.contains(toolName)
}
```

可见性 Map 的 **key 域只有 17 个**。再看判定函数（同文件 :19-27）：

```kotlin
fun isBuiltinToolAllowed(toolName: String): Boolean {
    if (!customEnabled) {
        return effectiveBuiltinToolVisibility[toolName] ?: true
    }
    return when (toolName) {
        "package_proxy" -> hasAnyAllowedExternalSource
        else -> effectiveBuiltinToolVisibility[toolName] == true   // 查不到 → null == true → false
    }
}
```

最后在注入模型前过滤（`EnhancedAIService.kt:3011`）：

```kotlin
categories.flatMap { it.tools }.toMutableList().apply {
    retainAll { tool -> roleCardToolAccess.isBuiltinToolAllowed(tool.name) }
}
```

**结论：只要角色卡开了自定义工具（`customEnabled = true`），146 个内部工具 + 分类外的工具全部被静默丢弃。** 模型收到的工具集里根本没有 `music_play`，所以它回答「music_play 不在我的内置工具列表中」——**它说的是实话**，也解释了为什么它转而去找别的软件放歌。

---

## 三、目标设计（按用户拍板）

工具的**使用方式**分两类，语义清晰、互不干扰：

| 类别 | 范围 | 授权方式 |
| --- | --- | --- |
| **内置工具** | 163 项（4 基础分类 17 + 内部 12 分类 146） | **总开关一开，全部可用**，无需逐项勾选 |
| **市场工具** | 包 / Skill / MCP | 在对应 Tab **手动勾选**，与现状一致 |

> 用户原话：「分成两类，一类是内置的，一个是市场下的，内置的话你开了开关之后都能用，市场的话你要勾选了，就和我视频给你那个一样」

---

## 四、改动清单

### 4.1 `SystemToolPrompts.kt` — 补齐可管理工具全集

`getManageableToolPrompts()` 纳入内部工具分类，作为「全量内置工具」的权威来源：

```kotlin
val baseCategories = if (useEnglish) {
    listOf(basicTools, fileSystemTools, httpTools, memoryTools) + internalToolCategoriesEn
} else {
    listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn) + internalToolCategoriesCn
}
```

> 保留 `distinctBy { it.name }` 去重；`ManageableToolPrompt` 已带 `categoryName`，UI 可按分类分组。

### 4.2 `CharacterCardToolAccessResolver.kt` — 修可见性基准

自定义工具开启时，内置工具**不再依赖逐项白名单**：

```kotlin
val allBuiltinNames = SystemToolPrompts
    .getManageableToolPrompts(useEnglish = false)
    .mapTo(LinkedHashSet()) { it.name }

val effectiveBuiltinToolVisibility = allBuiltinNames.associateWith { toolName ->
    val globalAllowed = effectiveGlobalToolVisibility[toolName] ?: true
    if (toolName == "package_proxy") {
        globalAllowed
    } else {
        // 内置工具：总开关开启即全部放行，但仍尊重全局单工具可见性设置
        globalAllowed
    }
}
```

要点：
- 内置工具**全量**进 Map，杜绝 `null == true → false` 的静默剔除。
- 仍尊重**全局**单工具可见性（设置页里关掉的工具不应被角色卡强行打开）。
- `use_package` 保持为市场工具的前置依赖（见 4.4）。

### 4.3 `CharacterCard.kt` — 配置模型语义调整

`allowedBuiltinTools` 语义从「白名单」变为「**旧版兼容字段**」：

- 保留字段以兼容已存角色卡数据，避免老数据解析异常。
- 新增 `internalToolsEnabled: Boolean = true`（默认开）表示内置工具总开关。
- `normalized()` 与 `hasExternalSelections()` 逻辑同步调整。

### 4.4 `CharacterCardDialog.kt` — UI 与校验

- **内建工具 Tab**：不再逐项勾选。改为「内置工具：已启用 163 项」+ 总数说明，或按分类分组只读展示（默认全部开启）。
- **市场 Tab（包/Skill/MCP）**：保持现有勾选交互不变。
- **保存校验**（:926-937）：`use_package` 前置检查仅对**市场工具**生效，不再因内置工具未勾 `use_package` 而拦截保存。

### 4.5 `strings.xml` / `values-en/strings.xml`

- `character_card_tool_access_summary_counts` 文案调整（内置工具不再展示勾选数）。
- 新增内置工具总开关说明文案（中英各 1 条）。
- 保留 4 个 Tab 名称不变。

---

## 五、风险与回归点

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| 工具数从 17 → 163 | 开启同一开关后，模型可见工具数显著增加，提示词变长 | FULL 模式本就暴露 183 个；此次改动是「修 bug」而非「扩权」，符合既有设计 |
| 老角色卡数据 | 已保存的 `allowedBuiltinTools` 列表可能只含少量项 | 字段保留 + 迁移时按新语义忽略该列表 |
| `use_package` 依赖 | 市场工具仍需此工具可用 | 4.4 中已把校验限定在市场侧 |
| CLI 模式 | `CliToolModeSupport` 走独立通道（仅 2 个代理工具） | 本次改动不涉及，需单独回归 |
| 全局工具可见性 | 用户可能在设置页关掉了某些内置工具 | 4.2 中保留 `effectiveGlobalToolVisibility` 叠加 |

---

## 六、验证计划

1. `kotlinc` 静态编译检查（改动文件无语法/类型错误）
2. 推 CI，等 Tests + Build 双绿
3. 真机验证：
   - 角色卡开启自定义工具 → 对话「放首歌」→ `music_play` 被调用
   - 白名单页内建工具 Tab 显示全量 163 项（或分组视图）
   - 市场 Tool Tab 勾选/取消行为与改前一致
   - 关闭自定义工具 → 行为回到跟随全局
   - 老角色卡（改动前保存的）打开不崩溃
