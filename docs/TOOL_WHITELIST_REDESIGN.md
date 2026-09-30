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

## 四、改动清单（已于 commit `8ed5e03b` 落地）

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
val manageableBuiltinNames = SystemToolPrompts
    .getManageableToolPrompts(useEnglish = false)
    .mapTo(LinkedHashSet()) { it.name }          // 现在是全部 163 项

val builtinToolsEnabled = roleCardConfig.builtinToolsEnabled
val effectiveBuiltinToolVisibility = manageableBuiltinNames.associateWith { toolName ->
    val globalAllowed = effectiveGlobalToolVisibility[toolName] ?: true
    when {
        toolName != "package_proxy" -> globalAllowed && builtinToolsEnabled
        else -> globalAllowed
    }
}
```

要点：
- 内置工具**全量**进 Map，杜绝 `null == true → false` 的静默剔除。
- 内置工具由 `builtinToolsEnabled` 总开关统一放行。
- 仍尊重**全局**单工具可见性（设置页里关掉的工具不应被角色卡强行打开）。
- `package_proxy` 是市场工具入口，保持由外部源配置决定。
- `use_package` 自然随总开关一起放行，市场工具仍受 `canUsePackageSystem` 约束。

### 4.3 `CharacterCard.kt` — 配置模型语义调整

`allowedBuiltinTools` 语义从「白名单」变为「**旧版兼容字段**」：

- 保留字段以兼容已存角色卡数据，避免老数据解析异常。
- 新增 `builtinToolsEnabled: Boolean = true`（默认开）表示内置工具总开关。
- 该字段在 `normalized()` 中仍做 trim/dedupe，但不参与授权判定。

### 4.4 `CharacterCardDialog.kt` — UI 与校验

- **内建工具 Tab**：改为「总开关 + 分类只读列表」。总开关标题「启用全部内置工具」，
  列表项保留名称 + `分类 · 描述` 副标题，右侧图标（✓/✗）随开关联动，不再提供勾选框。
- **市场 Tab（包/Skill/MCP）**：保持原有逐项勾选交互不变。
- **保存校验**：原 `use_package` 检查改为「开启市场工具但内置总开关关闭」时提示，
  不再因内置工具而拦截保存。
- **计数条**：`内置工具 %1$s · 包 %2$d · Skill %3$d · MCP %4$d`，内置项显示
  「全部启用 / 已关闭」。

### 4.5 `strings.xml` / `values-en/strings.xml`

- `character_card_tool_access_summary_counts` 首参由 `%1$d` 改为 `%1$s`（改为文案）。
- 新增 `character_card_tool_access_summary_builtin_on/off`、
  `character_card_tool_access_builtin_master_title/subtitle`。
- 更新 `character_card_tool_access_requires_use_package`、`..._empty_builtin` 措辞。

### 4.6 `StandardSoftwareSettingsModifyTools.kt` — 工具调用面同步

`create_character_card` / `update_character_card` 新增 `builtin_tools_enabled` 参数
（布尔），让 AI 通过工具调用也能配置总开关；`strings.xml` 中工具描述同步说明
`allowed_builtin_tools` 已降级为兼容字段。

---

## 五、风险与回归点

| 风险 | 说明 | 处理 |
| --- | --- | --- |
| 工具数从 17 → 163 | 开启同一开关后，模型可见工具数显著增加，提示词变长 | FULL 模式本就暴露 183 个；此次改动是「修 bug」而非「扩权」，符合既有设计 |
| 老角色卡数据 | 已保存的 `allowedBuiltinTools` 列表可能只含少量项 | 字段保留（不删 key），运行时按新语义忽略该列表；序列化兼容 |
| `use_package` 依赖 | 市场工具仍需此工具可用 | 由 `builtinToolsEnabled` 总开关统一放行，`canUsePackageSystem` 逻辑不变 |
| 全局工具可见性 | 用户可能在设置页关掉了某些内置工具 | 4.2 中保留 `effectiveGlobalToolVisibility` 叠加，全局优先 |
| 白名单列表变长 | 163 项在弹窗内滚动 | 保留搜索框（匹配 key/title/subtitle）；列表容器已有 `heightIn(max = 320.dp)` + `verticalScroll` |
| CLI 模式 | `CliToolModeSupport` 走独立通道（仅 2 个代理工具） | 本次改动不涉及，需单独回归 |
| `allowedBuiltinTools` 语义变更 | 若外部脚本/用户依赖该项做授权 | 工具描述已明确标注为兼容字段；UI 不再呈现该项 |

---

## 六、验证计划

1. **静态检查**：改动文件括号/结构平衡校验（已做）；`strings.xml` XML 合法性校验（已做）。
2. **CI**：推送 `8ed5e03b` 后 `Android Tests` + `Android Build` 双绿。
3. **真机验证清单**（新 APK）：
   - [ ] 角色卡开启自定义工具 → 对话「放首歌」→ `music_play` 被调用并播放
   - [ ] 白名单页内建工具 Tab 显示全量 163 项，总开关默认开启
   - [ ] 关闭总开关 → 列表图标变 ✗，AI 不再持有内置工具
   - [ ] 市场 Tab（包/Skill/MCP）勾选/取消行为与改前一致
   - [ ] 计数条显示「内置工具 全部启用 · 包 N · Skill N · MCP N」
   - [ ] 关闭角色卡自定义工具 → 行为回到跟随全局
   - [ ] 改动前保存的老角色卡打开不崩溃、字段不丢
   - [ ] 无网络/无图片识别服务时，`read_file` 的 `intent` 参数不出现

