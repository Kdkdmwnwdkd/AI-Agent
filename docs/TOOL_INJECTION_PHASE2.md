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

