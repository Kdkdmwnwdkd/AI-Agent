# 音乐工具调用失败 · 根因确认（最终版）

> **日期**：2026-09-29 初版 / **2026-09-30 修订**
> **状态**：✅ **根因已确证并已修复**（commit `8ed5e03b`）
> **性质**：**真 bug**（角色卡白名单漏掉全部内部工具）
> **与本次改动关系**：**无关**，属 Operit 上游既有缺陷

---

## ⚠️ 阅读提示：本文档已修订

初版把根因定在「`package_proxy` 通道选错」。**那个判断是错的**——
通道误导只是**表象**，真正的原因是**白名单根本不给模型这个工具**。

**真根因**见下方「零、最终结论」，完整分析见 `TOOL_WHITELIST_REDESIGN.md`。

---

## 零、最终结论（2026-09-30 确证）

### 现象

角色卡开启「自定义允许使用的工具」后，AI **完全无法调用 `music_play`**，
并明确回复「music_play 不在我的内置工具列表中」。白名单页也看不到任何音乐工具。

### 根因：双层 bug

**第一层 · UI 不渲染**

`core/config/SystemToolPrompts.kt:751` `getManageableToolPrompts()` 写死只取 4 类：

```kotlin
val baseCategories = if (useEnglish) {
    listOf(basicTools, fileSystemTools, httpTools, memoryTools)   // 共 17 个工具
} else {
    listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn)
}
```

而 146 个内部工具（含整个 `music_*` 家族）定义在 `SystemToolPromptsInternal.kt`
的 12 个分类里，**从未被纳入** → 白名单页结构性缺失。

**第二层 · 逻辑静默剔除（更致命）**

`data/preferences/CharacterCardToolAccessResolver.kt:92` 用同一份 17 项子集
构造可见性 Map 的 key 域：

```kotlin
val manageableBuiltinNames = SystemToolPrompts
    .getManageableToolPrompts(useEnglish = false)   // 只有 17 个
    .mapTo(LinkedHashSet()) { it.name }
val effectiveBuiltinToolVisibility = manageableBuiltinNames.associateWith { ... }
```

判定函数（同文件 `:19-27`）：

```kotlin
fun isBuiltinToolAllowed(toolName: String): Boolean {
    if (!customEnabled) return effectiveBuiltinToolVisibility[toolName] ?: true
    return when (toolName) {
        "package_proxy" -> hasAnyAllowedExternalSource
        else -> effectiveBuiltinToolVisibility[toolName] == true   // 查不到 → null == true → false
    }
}
```

→ 只要角色卡开了自定义工具（`customEnabled = true`），**146 个内部工具全部被
`EnhancedAIService.kt:3011` 的 `retainAll` 静默剔除**。用户手工填写配置也无效。

**所以模型说「music_play 不在我的内置工具列表中」——它说的是实话。**

### 修复

| 文件 | 改动 |
|---|---|
| `SystemToolPrompts.kt` | `getManageableToolPrompts()` 追加 `internalToolCategoriesEn/Cn`，17 → 163 |
| `CharacterCardToolAccessResolver.kt` | 可见性基准改为全量内置工具，不再误杀 |
| `CharacterCard.kt` | 新增 `builtinToolsEnabled` 总开关；`allowedBuiltinTools` 降级为兼容字段 |
| `CharacterCardDialog.kt` | 内建工具 Tab 改「总开关 + 只读列表」；修保存校验 |
| `StandardSoftwareSettingsModifyTools.kt` | 新增 `builtin_tools_enabled` 参数 |

设计语义：**内置工具**由总开关统一放行（163 项全开），**市场工具**（包/Skill/MCP）
仍需逐项勾选。

---

## 一、旧版分析（通道误导，保留存档）

以下是初版判断。**方向错了**——它不是根因，但如果将来真的出现
「模型主动把内置工具塞进 `package_proxy`」的情况，下面的引导语改动仍然有效
（已随 `2b5f39cc` 落地）。

### 1.1 报错演进

| 阶段 | 模型行为 | 报错 | 含义 |
|---|---|---|---|
| 1 | 调 `package_proxy`，**没传 `tool_name`** | `Exactly one tool_name parameter is required` | 参数缺失 |
| 2 | 角色卡配好后，调 `package_proxy(tool_name="music_play")` | `tool_name must use packageName:toolName format` | 通道错了 |
| — | **期望行为** | — | 应直接调 `music_play`，或经 `proxy` 转调 |

### 1.2 报错来源

`core/tools/ToolRegistration.kt:159`

```kotlin
if (requireQualifiedTarget && !targetToolName.contains(':')) {
    return null to buildToolErrorResult(
        tool,
        "tool_name must use packageName:toolName format"   // ← 用户看到的报错
    )
}
```

### 1.3 两个代理工具的唯一差别

| 工具 | 注册位置 | `requireQualifiedTarget` | 目标类型 |
|---|---|---|---|
| `proxy` | `ToolRegistration.kt:1155` | **`false`** | 内置 / 隐藏工具 |
| `package_proxy` | `ToolRegistration.kt:1211` | **`true`** | **包**工具（必须 `包名:工具名`） |

### 1.4 已落地的缓和措施

`2b5f39cc` 在报错串后追加引导语，并在 `package_proxy` 描述里写明边界：

> 如果 `xxx` 是内置工具，请直接调用它，不要经过 `package_proxy`。

真机复测显示模型**读懂了引导语**，但随即承认「music_play 不在我的内置工具列表中」
——这条反馈正是发现真根因（白名单缺失）的线索。

---

## 二、CLI 模式疑点（已排除）

初版怀疑「为何进了 CLI 模式」。后续确认：用户使用的是 `DEEPSEEK` →
`ToolExposureMode.resolve()` 返回 `FULL` → 183 个工具全部直接暴露，
`proxy` / `package_proxy` 转调与内置工具无关。**此疑点作废。**

---

## 三、这**不是**本次改动引入的

| 本次改动 | 是否影响 |
|---|---|
| Media3 迁移 | ❌ 只改播放实现 |
| Coil 3 迁移 | ❌ 图片库 |
| 删 11 项依赖 | ❌ 无工具路由相关 |
| 工具描述重写 | ❌ 只改文案 |
| retryable 字段 | ❌ 只改失败标记 |

**属 Operit 上游既有设计问题。**

---

## 四、好消息（已验证的部分）

用户 mp4 证实**主题背景视频正常播放**：

→ `app:keep_content_on_player_reset` **被 Media3 正确支持** ✅
→ **Media3 迁移核心风险已排除**
→ **播放能力本身完好**，坏的只是"角色卡不给 AI 这个工具"

---

## 五、诚实边界

1. **真根因（白名单缺失）已确证**，有代码路径双向印证，且已修复推 CI
2. 初版「通道选错」判断**已作废**，保留仅为存档
3. 我**没有 Operit 的运行日志**，无法单方面断言运行时行为
4. 本文件与仓库 `docs/MUSIC_TOOL_ROOT_CAUSE.md`、资料库同步

