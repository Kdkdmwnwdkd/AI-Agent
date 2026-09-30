# 内部工具注入 · 第二步规划：分类与按需展开

> **前置**：第一步（`aca450ab`）已把 146 个内部工具接入对话注入通道，模型可见工具 17 → 162。
> **本步目标**：解决"162 个工具全塞进提示词导致模型涣散"的问题。
> **用户设计原话**：「给内置模型分个类，放歌的就放歌类型里面，该改代码就改代码里面，把模型分个类，那么他要找的时候就找那个类型里面的模型，就不会说提示词太多了」

---

## 一、问题陈述

第一步为了"先保证功能可用"，选择了**全量注入**。代价：

| 指标 | 现状 | 影响 |
| --- | --- | --- |
| 模型可见工具数 | 162 | 单个工具描述含参数表 + 使用说明，提示词显著膨胀 |
| 典型症状 | 模型数不清自己有哪些工具 | 真机已复现：AI 自述"我实际拥有的工具只有 17 个"，漏报严重 |
| 长提示词风险 | DeepSeek 等模型易注意力涣散 | 越靠后的工具越容易被忽略（如 `music_*` 在列表尾部） |

---

## 二、可复用的现成设施

**好消息**：CLI 模式里已有一套完整的「工具目录 + 关键词检索」实现，无需从零造。

`core/tools/climode/CliToolModeSupport.kt`：

| 组件 | 位置 | 作用 |
| --- | --- | --- |
| `HiddenToolCatalogEntry` | `:55` | 目录条目：工具名、描述、参数提示、来源类型、关键词 |
| `buildHiddenToolCatalog(...)` | `:202` | 构建全量目录（含 internal 分类，已按角色卡权限过滤） |
| `searchHiddenToolCatalog(catalog, query, limit)` | `:322` | **关键词打分检索**，按分排序，`limit` 上限 20 |
| `formatSearchResults(...)` | `:353` | 把检索结果格式化成模型可读文本 |
| `scoreEntry(...)` | 私有 | 打分：工具名 / 描述 / 关键词 / 分类名 加权匹配 |

**关键**：`buildHiddenToolCatalog` 内部用 `buildBuiltinAndInternalCategories`（= `getAllCategories`，含内部工具），
且已通过 `isToolNameAllowedForRoleCard` 做了角色卡权限过滤 —— **与我们的白名单语义天然一致**。

---

## 三、两个候选方案

### 方案 A：两段式（目录 → 检索 → 调用）

**做法**：
1. 提示词只给 12 个**分类名 + 一句话说明**（约 12 行）
2. 模型需要某类工具时，调用 `search_tools(query="播放音乐")`
3. 拿到结果后直接调用目标工具

**优点**：提示词最小化；复用 CLI 现成的检索实现
**缺点**：多一次往返；依赖模型主动检索（可能不查就直接说"我没有"）

### 方案 B：常驻精选 + 分类兜底 ★推荐

**做法**：
1. **高频工具常驻**：`music_*`（8个）、`tap`、`capture_screenshot`、`send_notification` 等直接注入
2. **其余走分类**：提示词列出 12 个分类名，模型按需检索
3. 检索机制同方案 A

**优点**：高频操作一步到位（放歌、截图不用绕）；提示词仍显著小于全量
**缺点**：常驻清单需维护

**推荐理由**：放歌、截图、点屏幕是日常高频动作，让它们多绕一步不值得；
而 `bluetooth_ble_read_characteristic` 这类一年用不上一次的，藏起来零损失。

---

## 四、实施要点（待第一步验证通过后执行）

### 4.1 新增检索工具

在 FULL 模式下注册一个内置工具（名字待定，如 `search_tools`），
内部转调 `CliToolModeSupport.searchHiddenToolCatalog`。

需注意：`CliToolModeSupport` 当前是 CLI 专用，需要把目录/检索部分
提取为中立组件，供 FULL 模式共用。**不可直接复制代码** —— 会造成双份维护。

### 4.2 定义常驻清单

```kotlin
// 高频工具：始终注入，无需检索
private val ALWAYS_INJECTED_TOOLS = setOf(
    // 音乐
    "music_play", "music_pause", "music_resume", "music_stop",
    "music_play_queue", "music_seek", "music_set_volume", "music_status",
    // 常用交互
    "tap", "capture_screenshot", "send_notification",
    // 文件分享
    "share_file", "open_file"
)
```

### 4.3 分组提示词生成

模型收到的提示词结构改为：

```
【内置工具·常用】（直接调用）
  music_play / music_pause / ... 各自完整描述

【内置工具·其他】（按分类检索）
  可用分类：内部工具(42) / 内部系统工具(32) / 软件设置工具(16) / ...
  需要某类工具时，调用 search_tools(query="关键词") 获取详情
```

### 4.4 兼容性

- CLI 模式：已有独立通道，**不改动**
- 角色卡白名单：总开关语义不变，仍控制"是否允许内置工具"
- 工作流：`getAllCategories` 行为不变

---

## 五、验证计划

1. 静态检查 + CI 双绿
2. 真机验证：
   - [ ] 放歌：对话"放首歌" → 直接调 `music_play`（不需检索）
   - [ ] 冷门工具：对话"读一下我的蓝牙设备列表" → 模型先 `search_tools` 再调用
   - [ ] 提示词长度明显小于全量注入版本
   - [ ] 模型能正确列出自己的工具（不再数错）
   - [ ] CLI 模式行为不变
   - [ ] 角色卡总开关关闭 → 内置工具全部不可用

---

## 六、当前状态

- [x] 第一步：内部工具接入注入通道（`aca450ab`）——**真机已验证通过**（音乐正常播放）
- [x] 补充修复：工具清单注入前按名字去重（`ef74abaa`，修 DeepSeek 400 拒收）
- [x] 第二步：分类/按需展开（本文件 §七）

---

## 七、第二步实现记录

### 7.1 最终方案（方案 B 变体）

采用**「常驻精选 + 目录检索」**，但没有新造工具 —— **直接复用 CLI 模式已有的 `search`**。

| 决策 | 内容 | 理由 |
| --- | --- | --- |
| 检索工具名 | 复用 `search`（`CliToolModeSupport.SEARCH_TOOL_NAME`） | 已有注册、已有权限逻辑、已有打分排序；新造会双份维护 |
| FULL 模式是否给 `proxy` | **不给** | FULL 模式工具直接调用，不需要代理层 |
| `search` 的可用模式 | CLI + FULL 都放行 | 用 `isSearchToolAllowed(mode, name)` 统一判定 |
| 目录来源 | 复用 `buildHiddenToolCatalog` | 已含角色卡权限过滤，语义天然一致 |
| 分类切分 | `SystemToolPrompts.splitAlwaysOnAndSearchable()` | 常驻部分复用 `filterAlwaysOnCategories`，与 `alwaysOnOnly` 路径同源 |

### 7.2 改动清单

| 文件 | 改动 |
| --- | --- |
| `SystemToolPrompts.kt` | 新增 `ALWAYS_ON_TOOL_NAMES` / `ALWAYS_ON_BASE_TOOL_NAMES`（共 30 个）、`isAlwaysOnTool()`、`filterAlwaysOnCategories()`、`splitAlwaysOnAndSearchable()`、`buildHiddenToolDirectorySummary()`；`getAIAllCategoriesEn/Cn` 新增 `alwaysOnOnly` 参数 |
| `CliToolModeSupport.kt` | 新增 `isSearchToolAllowed()` / `isProxyToolAllowed()`；把 `search` 定义抽成 `buildToolCatalogSearchPrompt(useEnglish, mode)`，`buildCliPublicToolPrompts` 复用之（删除了旧的重复定义） |
| `EnhancedAIService.kt` | FULL 分支改为「常驻分类 + `search`」，不再全量注入 |
| `SystemPromptConfig.kt` | FULL + Tool Call API 分支注入「隐藏工具目录概要」（分类名 + 数量） |
| `ToolExecutionManager.kt` | FULL 模式下放行 `search`（`proxy` 仍拒）；两者都免权限检查 |
| `ToolRegistration.kt` | `search` 执行校验从「仅 CLI」放宽为「CLI 或 FULL」 |

### 7.3 体量收益（实测）

用真实工具清单渲染 `ToolPrompt.toString()` 得出：

```
全量注入      : 42,962 字符  (165 工具)
常驻注入      :  9,490 字符  ( 30 工具)
目录概要      :    112 字符  (  2 行分类)
瘦身后        :  9,602 字符
节省          : 33,360 字符  (77.7%)
```

### 7.4 放行矩阵（已验证）

| 模式 | `search` | `proxy` | 其它工具 |
| --- | --- | --- | --- |
| FULL | ✅ 允许 | ❌ 拒绝 | 直接调用 |
| CLI | ✅ 允许 | ✅ 允许 | 拒绝（须走 proxy） |

### 7.5 常驻清单（30 个）

- **音乐 8**：`music_play/play_queue/pause/resume/stop/seek/set_volume/status`
- **交互 2**：`tap`、`capture_screenshot`
- **输出 3**：`send_notification`、`share_file`、`open_file`
- **基础 2**：`sleep`、`use_package`
- **文件 10**：`list_files`、`read_file`、`read_file_part`、`create_file`、`edit_file`、`delete_file`、`make_directory`、`find_files`、`grep_code`、`grep_context`
- **网络 3**：`visit_web`、`web_search`、`http_request`
- **记忆 2**：`query_memory`、`get_memory_by_title`

### 7.6 待真机验证

- [ ] 放歌：对话"放首歌" → 直接调 `music_play`（不需检索）
- [ ] 冷门工具：对话"列出蓝牙设备" → 先 `search` 再直接调用
- [ ] 模型能正确列出自己的工具（不再数错）
- [ ] CLI 模式（本地小模型）行为不变
- [ ] 角色卡内置工具总开关关闭 → 内置工具全部不可用

### 7.7 已知偏差

- 目录概要的数量**未按角色卡权限过滤**（用的是全量分类），只影响显示的数字，不影响 `search` 实际返回结果（那里有过滤）。可接受。

### 7.8 影响面清单

| 调用方 | 是否受影响 | 说明 |
| --- | --- | --- |
| `EnhancedAIService` FULL 注入 | ✅ 改 | 全量 → 常驻 30 + `search` |
| `EnhancedAIService` CLI 注入 | ⚠️ 间接 | `buildCliPublicToolPrompts` 改为复用 `buildToolCatalogSearchPrompt(CLI)`，**返回集不变**（search + proxy，已实测验证），仅 search 描述文案更准确 |
| `SystemPromptConfig` CLI 分支 | ❌ 不变 | `buildCliModePrompt` 未动 |
| `SystemPromptConfig` FULL + ToolCallAPI | ✅ 改 | 新增目录概要 |
| `SystemPromptConfig` XML 分支 | ❌ 不变 | 走 `availableTools`，不受影响 |
| `ToolExecutionManager` 权限检查 | ⚠️ 放宽 | FULL 下 `search` 免权限检查（原来会走正常检查）。`search` 只读本机工具目录、不触碰任何设备能力，安全 |
| `ToolExecutionManager` 曝光校验 | ⚠️ 放宽 | FULL 下 `search` 不再被拒 |
| `ToolRegistration` search 执行校验 | ⚠️ 放宽 | 同上 |
| `ToolRegistration` proxy 执行校验 | ❌ 不变 | 仍要求 CLI |
| `CliToolModeSupport.buildHiddenToolCatalog` | ❌ 不变 | 复用，未改 |
| `CliToolModeSupport.searchHiddenToolCatalog` | ❌ 不变 | 复用，未改 |
| 工作流 / `getAllCategories` | ❌ 不变 | 仍返回全量 |
| `getManageableToolPrompts` | ❌ 不变 | 白名单页仍显示全量 163 |

### 7.9 验证方法（沙箱内）

沙箱**无法跑完整 Gradle 构建**（缺 android.jar 与外部依赖包）。采用的替代验证：

1. **逻辑等价探针**（`/tmp/synt2/`）：把改动后的方法**原样抽出**，配从真实源码解析出的 165 个工具数据，编译运行。
   - 结果：常驻 30 + 待检索 135 = 165，**无丢失无重复**；常驻筛选 100% 命中清单。
2. **放行矩阵探针**：验证 FULL/CLI × search/proxy 的 8 种组合，全部符合预期。
3. **体量渲染**：按 `ToolPrompt.toString()` 真实规则统计。

> ⚠️ **不要靠括号平衡判断代码正确性**。`EnhancedAIService.kt` 的括号统计恒为 993/992（原始文件即为 1，因字符字面量 `'('` 被误算），与改动无关。必须用「原样抽取 + 真实数据 + 编译运行」的方式验证。



---

## 八、真机验证期发现的三件事（均非本阶段回归）

以下三个现象在真机验收时被报告，逐一排查后确认**全部与第二阶段改动无关**，记录在此以免后续重复排查。

### 8.1 关闭「启用角色卡级工具白名单」后，工具仍可被调用

**现象**：角色卡里关掉白名单、工具一个都不勾，AI 照样能调 `find_files` / `music_play`，只是改成弹权限确认框。

**根因**（`CharacterCardToolAccessResolver.resolve()` 第 76-89 行）：

```kotlin
if (!roleCardConfig.enabled) {
    return ResolvedCharacterCardToolAccess(
        customEnabled = false,          // ← 关键
        effectiveBuiltinToolVisibility = effectiveGlobalToolVisibility,
        ...
    )
}
```

`customEnabled = false` 时，`isBuiltinToolAllowed` 走第一行短路：

```kotlin
if (!customEnabled) { return effectiveBuiltinToolVisibility[toolName] ?: true }  // 缺省 true
```

→ **全部放行**。

**定性**：这是**既有设计**，非 bug，也非本阶段引入。语义是「关闭角色卡级限制 = 移除该限制 = 回落到全局默认」，而全局默认是放行。

> ⚠️ 该设计**反直觉**：用户以为"关掉白名单更安全"，实际是"限制没了"。
> 页面原有提示「当前跟随全局配置」已改为更明确的措辞（见 §8.4）。

**若要让白名单真正拦死工具**，需：**开启**第一层「启用角色卡级工具白名单」+ **关闭**第二层「启用全部内置工具」。此时走严格分支：

```kotlin
val builtinToolsEnabled = roleCardConfig.builtinToolsEnabled
val effectiveBuiltinToolVisibility = manageableBuiltinNames.associateWith { toolName ->
    val globalAllowed = effectiveGlobalToolVisibility[toolName] ?: true
    when {
        toolName != "package_proxy" -> globalAllowed && builtinToolsEnabled   // ← 第二层在这里生效
        else -> globalAllowed
    }
}
```

此时 `isBuiltinToolAllowed` 走 `effectiveBuiltinToolVisibility[toolName] == true` 严格判定 → `false` → 拦死。

**该组合尚未真机验证**，标注为盲区。因为拦截发生在 `ToolExecutionManager.executeInvocations` 的第 2 步（`isInvocationAllowedForRoleCard`），**优先于权限弹窗**，预期表现为"直接拒绝，不弹窗"。

### 8.2 执行低风险工具（如 `music_play`）时弹出权限确认框

**现象**：播放音乐时弹出「权限请求」浮窗，需点「允许」。

**根因**（`ToolPermissionSystem.checkToolPermission()` 第 196-210 行）：

```kotlin
val masterSwitch = PermissionLevel.fromString(preferences[MASTER_SWITCH] ?: DEFAULT_MASTER_SWITCH)
val overrideLevel = preferences[key]?.let { PermissionLevel.fromString(it) }

val permissionLevel = overrideLevel ?: when {
    SensitiveToolRegistry.isSensitive(tool.name) -> PermissionLevel.ASK
    else -> masterSwitch          // ← music_play 走这里
}
```

而 `DEFAULT_MASTER_SWITCH = PermissionLevel.ASK.name` → **主开关默认 ASK**。

`music_play` 虽在 `ALWAYS_SAFE_TOOLS` 白名单中，但该集合**只用于让 `isSensitive` 返回 false**，不参与"主动放行"判定。所以它落到 `else -> masterSwitch` → 默认 ASK → 弹窗。

**定性**：**设计取舍**，非 bug，非本阶段引入。保守默认（宁可多问）。

**解决方式**（无需改代码）：
- 弹窗里点「**以后都允许**」→ 写入 `tool_permission_<name> = ALLOW`
- 或 **设置 → 工具权限 → 全局权限开关 → 改为「允许」**

**副作用提醒**：主开关改 ALLOW 后，所有非高风险工具（约 90+ 个）不再弹窗，仅 `HIGH_RISK_TOOLS` 仍强制确认。`requestPermission` 超时为 **60 秒**（`PERMISSION_REQUEST_TIMEOUT_MS`），超时任务失败。

### 8.3 本地模型（GGUF）"找不到"

**现象**：本地模型列表为空。

**根因**：模型文件目录不符合 Operit 扫描路径。

- 实际位置：`/storage/emulated/0/AI 模型/xxx.gguf`
- Operit 扫描：`/storage/emulated/0/Download/Operit/models/llama/`

**定性**：**非代码问题**，移动文件即可。

### 8.4 本次伴随的文案/注释修正（零逻辑改动）

| 文件 | 位置 | 改动 |
|---|---|---|
| `core/tools/SensitiveToolRegistry.kt` | `ALWAYS_SAFE_TOOLS` 文档注释 | 原写「永远不会弹出确认」（**与实现不符**）→ 改为准确描述：仅影响 `isSensitive`，是否弹窗由主开关决定 |
| `res/values/strings.xml` | `tool_permissions_description` | 补充说明「低风险工具默认也会询问」+ 指向全局开关 |
| `res/values/strings.xml` | `global_permission_switch_description` | 补充说明「设为允许后高风险工具仍会确认」 |
| `res/values/strings.xml` | `character_card_tool_access_follow_global` | 「当前跟随全局配置」→「当前跟随全局配置（角色卡级限制未生效，工具可用性由全局决定）」 |

以上均为文案层面，**不改变任何运行时行为**。Kotlin 注释改动已通过编译探针验证。

### 8.5 验证矩阵（更新）

| 配置组合 | 第一层 `enabled` | 第二层 `builtinToolsEnabled` | 预期行为 | 验证状态 |
|---|---|---|---|---|
| A | ❌ 关 | 任意 | 全放行 + 弹权限 | ✅ 真机验证（视频） |
| B | ✅ 开 | ✅ 开 | 全放行 | 未验证 |
| C | ✅ 开 | ❌ 关 | **拦死，不弹窗** | ⚠️ **未验证（盲区）** |

> 组合 C 是唯一能真正"禁用内置工具"的配置，但因为拦截早于权限弹窗，其表现（拒绝 vs 弹窗）尚未在真机上确认。
