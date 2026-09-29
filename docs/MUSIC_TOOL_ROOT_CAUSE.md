# 音乐工具调用失败 · 根因确认（最终版）

> **日期**：2026-09-29
> **状态**：✅ **根因已确证**，定位到具体代码行
> **性质**：**真 bug**（工具通道选择错误 + 可能被误判为 CLI 模式）
> **与本次改动关系**：**无关**，属 Operit 上游既有缺陷

---

## 一、报错演进（三次，逐次逼近真相）

| 阶段 | 模型行为 | 报错 | 含义 |
|---|---|---|---|
| 1 | 调 `package_proxy`，**没传 `tool_name`** | `Exactly one tool_name parameter is required` | 参数缺失 |
| 2 | 角色卡配好后，调 `package_proxy(tool_name="music_play")` | `tool_name must use packageName:toolName format` | **通道错了** |
| — | **期望行为** | — | 应直接调 `music_play`，或经 `proxy` 转调 |

---

## 二、根因（已定位到具体行）

### 2.1 报错来源

`app/src/main/java/com/ai/assistance/operit/core/tools/ToolRegistration.kt:159`

```kotlin
if (requireQualifiedTarget && !targetToolName.contains(':')) {
    return null to buildToolErrorResult(
        tool,
        "tool_name must use packageName:toolName format"   // ← 用户看到的报错
    )
}
```

### 2.2 两个代理工具的唯一差别

| 工具 | 注册位置 | `requireQualifiedTarget` | 目标类型 |
|---|---|---|---|
| `proxy` | `ToolRegistration.kt:1155` | **`false`** | 内置 / 隐藏工具 |
| `package_proxy` | `ToolRegistration.kt:1211` | **`true`** | **包**工具（必须 `包名:工具名`） |

**模型调的是 `package_proxy`，传的是 `music_play`（内置工具，无冒号）→ 必然报错。**

**正确通道是 `proxy`。**

### 2.3 关键补充：`music_play` 是内置工具

```
SystemToolPromptsInternal.kt:175   (EN)  → categoryName = "Internal Tools"
SystemToolPromptsInternal.kt:3167  (CN)
```

→ 属 `HiddenToolSourceKind.INTERNAL`，**不是 `PACKAGE`**，**不该走 `package_proxy`**。

---

## 三、第二个疑点：为什么进了 CLI 模式？

### 3.1 代码预期

`CliToolModeSupport.kt:24`

```kotlin
fun resolve(providerType: ApiProviderType): ToolExposureMode {
    return when (providerType) {
        LMSTUDIO, OLLAMA, OPENAI_LOCAL, MNN, LLAMA_CPP -> CLI
        else -> FULL
    }
}
```

`ApiProviderType.DEEPSEEK` **存在**（`ModelConfigData.kt:26`）且**不在 CLI 名单** → 应走 `FULL`。

→ **`FULL` 模式下模型直接看到 `music_play`，根本不会出现 `proxy` 这种转调。**

### 3.2 但用户实测它在用代理工具

→ 说明**实际走了 CLI**。可能原因：

1. 用户选的模型配置里 `apiProviderType` **不是 `DEEPSEEK`**（例如选了「自定义端点 / OpenAI 兼容」，被归到会走 CLI 的类型）
2. `EnhancedAIService` 三处调用点（L2162 / L2742 / L2965）传入的 `config` 不是当前对话模型的配置
3. 其他未查到的 CLI 入口

**这一条尚未确证，需要看用户的模型配置页。**

---

## 四、这**不是**本次改动引入的

| 本次改动 | 是否影响 |
|---|---|
| Media3 迁移 | ❌ 只改播放实现 |
| Coil 3 迁移 | ❌ 图片库 |
| 删 11 项依赖 | ❌ 无工具路由相关 |
| 工具描述重写 | ❌ 只改文案 |
| retryable 字段 | ❌ 只改失败标记 |

**属 Operit 上游既有设计问题。**

---

## 五、好消息（本次改动已验证的部分）

用户 mp4 证实**主题背景视频正常播放**：

→ `app:keep_content_on_player_reset` **被 Media3 正确支持** ✅
→ **Media3 迁移核心风险已排除**
→ **播放能力本身完好**，坏的只是"模型走错通道"

---

## 六、修复方向（两个问题，分开治）

### 🔴 问题 A：`package_proxy` 的错误信息不友好（低风险可修）

模型既已传了 `music_play`，说明它**意图正确、只是通道选错**。
当前报错 `tool_name must use packageName:toolName format` **没告诉它该走 `proxy`**。

**建议改法**（`ToolRegistration.kt:159`）：

```kotlin
if (requireQualifiedTarget && !targetToolName.contains(':')) {
    return null to buildToolErrorResult(
        tool,
        "tool_name must use packageName:toolName format. " +
        "If '$targetToolName' is a built-in/internal tool, " +
        "call 'proxy' instead of 'package_proxy'."
    )
}
```

**加一句引导 = 模型下一步自己就走对了。** 零逻辑改动，零风险。

### 🟠 问题 B：CLI 模式判定的真实原因（待查）

需要用户提供**模型配置页截图**（看 `apiProviderType` 是什么）。

若确为 `DEEPSEEK` 却走了 CLI → 是**判定 bug**，需进一步查三处调用点。

---

## 七、诚实边界

1. **根因（通道选错）已确证**，有代码行为与报错串双向印证
2. **"为何进 CLI 模式"未确证**，仅推断，需用户配置页佐证
3. 我**没有 Operit 的运行日志**，无法单方面断言运行时 `toolExposureMode` 的取值
4. 本文件与仓库 `docs/MUSIC_TOOL_ROOT_CAUSE.md`、资料库同步
