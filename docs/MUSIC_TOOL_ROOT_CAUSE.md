# 音乐工具调用失败 · 根因定位（修正版）

> **日期**：2026-09-29
> **现象**：让 Operit 调 `music_play`，模型返回 `Exactly one tool_name parameter is required`
> **修正说明**：初版推断"音乐工具未进提示词分类"**是错的**，已实测推翻，本文为修正版。

---

## 一、现象

用户强制约束后仍失败：

```
指令：调用 music_play 工具放歌，禁止 shell / 禁止其它 App
实际：→ package_proxy   music_play
      ↳ ✗ Exactly one tool_name parameter is required
```

**模型调的是 `package_proxy`，不是 `music_play`。**

---

## 二、实测推翻的假设

| 假设 | 验证方法 | 结论 |
|---|---|---|
| 音乐工具没进提示词分类 | `grep music_play SystemToolPromptsInternal.kt` | ❌ **错了**，`music_play` 在 L175（EN）/ L3167（CN），分类 `Internal Tools` |
| 音乐工具没注册 | `grep music_play ToolRegistration.kt` | ❌ 错了，L527，8 个音乐工具都在 |
| 用户模型该走 FULL | 读 `ToolExposureMode.resolve()` | ✅ 对，`deepseek-v4-flash` → `else -> FULL` |

**注册、分类、模式判定三处都正常。问题不在这。**

> ⚠️ 初版用正则比对时，`SystemToolPromptsInternal.kt` 里的 `ToolPrompt(` 换行格式
> 导致 159 个工具被误报为"未暴露"——**那个 159 的数字是假的，作废。**

---

## 三、真正的根因

### 3.1 `package_proxy` 是"包里工具"的转调入口

CLI 工具模式有 3 个代理工具：

| 工具 | 转调目标 |
|---|---|
| `search` | 搜索隐藏工具目录 |
| `proxy` | 转调**内置**隐藏工具 |
| `package_proxy` | 转调**包（package）**提供的工具 |

**模型选了 `package_proxy`** —— 说明它认为 `music_play` 是"包里"的工具，**判断错了**（`music_play` 是内置工具，该用 `proxy`）。

而且它**连 `tool_name` 参数都没传**，直接报错。

### 3.2 两种可能，需区分

| 可能 | 特征 | 性质 |
|---|---|---|
| **A. 模型确实在 CLI 模式** | 它眼前只有 `search`/`proxy`/`package_proxy`，看不到 `music_play` | 配置/判定问题 |
| **B. 模型在 FULL 模式但选错工具** | `music_play` 就在它面前，它却绕道走代理 | **模型能力问题** |

**从"它调 `package_proxy` 而非直接调 `music_play`"看，更像 A。**

### 3.3 若为 A，判定逻辑有盲区（已读源码）

```kotlin
// CliToolModeSupport.kt:24
fun resolve(providerType: ApiProviderType): ToolExposureMode {
    return when (providerType) {
        LMSTUDIO, OLLAMA, OPENAI_LOCAL, MNN, LLAMA_CPP -> CLI
        else -> FULL
    }
}
```

**判定只看 `apiProviderType` 一个维度。** 但调用点有 3 处：

```
EnhancedAIService.kt:2162
EnhancedAIService.kt:2742
EnhancedAIService.kt:2965
```

**这 3 处传入的 `config.apiProviderType` 是否都是 `deepseek`？**
若某一处传的是别的 provider（如被判定为本地模型），就会**意外进入 CLI 模式**。

**这是最值得查的地方。**

---

## 四、与本次改动的关系

**无关。** 逐项排除：

| 本次改动 | 是否影响工具暴露 |
|---|---|
| Media3 迁移 | ❌ 只改播放实现 |
| Coil 3 迁移 | ❌ 图片库 |
| 删 11 项依赖 | ❌ 无工具注册相关 |
| 工具描述重写 | ⚠️ **只改文案，不改可见性** |
| retryable 字段 | ❌ 只改失败标记 |

**这是 Operit 上游既有行为，非本次引入。**

---

## 五、好消息：Media3 已确认没问题

用户发的 mp4 显示**主题背景视频正常播放**。

→ `app:keep_content_on_player_reset` 自定义属性**被 Media3 正确支持** ✅
→ **Media3 迁移的核心风险项已排除**
→ **播放能力本身是好的**，坏的只是"模型调不到它"

---

## 六、需要用户做的（2 件事，都很快）

### ① 确认工具列表里有没有 `music_play`

Operit → 工具管理 → 搜索 `music_play`

- **有且已启用** → 是可能性 B（模型选错），**不是 bug**，属模型能力问题
- **找不到 / 被禁用** → 是可能性 A，**真 bug**，我立刻查

### ②（可选）直接给模型下"直连"指令

```
直接调用 music_play 工具（不要用 search / proxy / package_proxy 转调）。
参数 source_type=file, source=/sdcard/Music/xxx.mp3
```

若这样能成功 → 证明工具**是可见的**，纯粹是模型自己绕路 → 可能性 B

---

## 七、下一步

用户在「工具列表」确认后：

- **若是 B**：属提示词/模型能力问题。工具描述重写（本轮流）正是为缓解这类问题做的——
  可以在 `music_play` 描述里**显式加一句"直接调用本工具，不要经 proxy 转调"**，低风险可做
- **若是 A**：立刻查 `EnhancedAIService` 三处 `resolve()` 的入参，定位为何云端模型进了 CLI 模式

---

## 八、诚实边界

1. **初版推断（工具未暴露）已被实测推翻**，本文已更正；那个"159 个工具未暴露"的数字**作废**
2. **尚未确证是 A 还是 B** —— 需用户在真机上看工具列表
3. 我没有 Operit 的运行日志，**无法单方面断言它进了哪个模式**
4. 本文件与仓库 `docs/`、资料库同步
