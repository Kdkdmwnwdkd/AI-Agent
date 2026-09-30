# 高危缺陷修复说明（提交 `8a070e7`）

> 生成时间：2026-09-30
> 目的：记录本轮修复的三个高危缺陷的根因、修法、验证方式，供后续开发者与接手 AI 阅读。
> 关联：`PROJECT_CONTEXT.md`（项目上下文）、`AGENTS.md`（执行铁律）。

---

## 本轮修复清单

| 编号 | 缺陷 | 危害等级 | 影响范围 |
|---|---|---|---|
| **A3** | 聊天记录导入「先删后插」无事务 | 🔴 数据永久丢失 | 所有聊天记录导入/还原路径 |
| **A2** | 模型下载状态并发写同一 JSON 无锁 | 🔴 进度丢失 + 误判重下 | 多模型并发下载 |
| **B3** | `read_file_binary` 无大小上限 | 🔴 必现 OOM 杀进程 | 三个执行分支全部 |

---

## A3：聊天记录导入无事务

### 根因

`ChatHistoryManager.saveChatHistoryInternal()`（`data/repository/ChatHistoryManager.kt`）

该方法是**替换语义**：先把该会话的旧消息全部删除，再插入新消息。原实现只有 `chatMutex(history.id).withLock`——**互斥锁保证的是「不被并发调用」，但不保证「操作的原子性」**。两者是不同维度：

```
withLock  → 防止 A、B 两个协程同时执行
transaction → 防止「删了但没插」的中间态被持久化
```

原代码 5 个写操作分属 3 个 DAO，中间任何时刻进程被杀（用户切后台被系统回收、低内存 kill、崩溃），**该会话的历史消息就永久消失**，且无法恢复——因为旧数据已被 `deleteAllMessagesForChat` 提交。

### 修法

用 `database.withTransaction { }` 把 5 个写操作整体包起来：

| 操作 | DAO |
|---|---|
| `insertChat` | ChatDao |
| `deleteAllMessagesForChat` | MessageDao |
| `deleteAllVariantsForChat` | MessageVariantDao |
| `insertMessages` | MessageDao |
| `insertVariants` | MessageVariantDao |

同时移除了 `catch (e: Exception) { throw e }` —— 这是**无意义的空转发**，捕获后原样抛出等于没写，属于噪音代码。

### 为什么异常要传播出去（而不是吞掉）

事务内 `validateArchivedMessageVariants()` 校验失败会抛异常 → 异常传播出 `withTransaction` → **Room 触发回滚** → 删除操作被撤销，会话数据保持原样。这正是期望行为：**宁可导入失败，也不能导入一半把数据弄丢。**

### 验证

用假 Room 接口模拟结构，实测：

```
正常路径: committed=true  rolledBack=false
失败路径: committed=false rolledBack=true   ← 期望值
```

结论：异常正确传播出事务，Room 会回滚，不会留下「消息已删、新消息没插」的空会话。

---

## A2：模型下载状态并发写坏

### 根因

`MnnModelDownloadManager.savePersistentStates()`（`data/mnn/MnnModelDownloadManager.kt`）

```
applicationScope.launch {          ← 每次保存都新起一个协程
    File(...).writeText(jsonString) ← 无锁写同一文件
}
```

`persistentStates` 是 `ConcurrentHashMap`（**只保证单次 map 操作线程安全，不保证「读-序列化-写」整段安全**），而 `savePersistentStates()` 在 `addPersistentState()` / `removePersistentState()` 里被调用。

并发下载多个模型时：

1. 协程 A 读 map → 序列化 → 开始写文件
2. 协程 B 同时读 map → 序列化 → 同时写同一文件
3. 两次 `writeText` 内容**交错写入** → 文件被写坏（JSON 撕裂）

后果：下次启动 `loadPersistentStates()` 解析失败 → **所有下载进度丢失** → 已完成但未清理状态的模型被误判为未下载 → **重复下载**。

### 修法

加 `Mutex` 串行化「读 map → 序列化 → 写文件」整段：

```kotlin
private val persistStateMutex = Mutex()

private fun savePersistentStates() {
    applicationScope.launch {
        try {
            persistStateMutex.withLock {   // ← 关键
                ...
            }
        } catch (e: Exception) { AppLogger.e(...) }
    }
}
```

### 为什么选 Mutex，不选「临时文件 + rename」

| 方案 | 防「读到写一半」 | 防「丢更新」 |
|---|---|---|
| 临时文件 + rename | ✅ | ❌ 协程 A、B 各写一份，后写的覆盖先写的，A 的进度白丢 |
| **Mutex** | ✅ | ✅ 两个问题一起解决 |

### 验证

模拟 20 个模型状态并发写入（加长写入窗口放大并发问题）：

```
最终文件内容: ["model4",...,"model19"]   ← 完整合法 JSON
条目数=20（应为 20）
写入次数=20（应为 20）
全部成功=true
```

---

## B3：`read_file_binary` 无大小上限

### 根因

**上游原版就没有这个限制**（已核实 `AAswordman/Operit` 三处实现均无检查，`ToolExecutionLimits` 里也只有给文本用的 `MAX_FILE_READ_BYTES = 32_000`）。

三个分支全部直接读取后 Base64：

| 分支 | 位置 | 代码 |
|---|---|---|
| Android | `StandardFileSystemTools.kt:1540` | `file.readBytes()` |
| Linux | `LinuxFileSystemTools.kt:239` | `fs.readFileBytes(path)` |
| SAF | `SafFileSystemTools.kt:1713` | `input.use { it.readBytes() }` |

**内存峰值约为文件大小的 1.34 倍**（Base64 膨胀 33%，且编码时源字节与目标字符串同时存在）：

| 步骤 | 内存 |
|---|---|
| ① `readBytes()` | 文件大小，如 500MB |
| ② `Base64.encodeToString()` | +667MB |
| ③ 两者同时存在 | **峰值 ≈ 1.17GB** |

Android 单 App 堆上限通常 512MB（大内存设备）或 192-256MB（普通设备）。**读 300MB 文件即必爆。**

后果：不是返回失败，是 `OutOfMemoryError` → **整个 App 进程被系统杀掉** → 未保存的对话、正在跑的下载全部丢失。

### 修法

在 `ToolExecutionLimits` 新增统一判定方法 `rejectBinaryReadReason(fileSizeBytes: Long): String?`：

```kotlin
// 返回 null = 可读；返回非 null = 不可读，字符串即为失败原因
fun rejectBinaryReadReason(fileSizeBytes: Long): String? {
    // 第一层：硬上限 200MB，挡住离谱输入
    if (fileSizeBytes > MAX_BINARY_READ_BYTES) return "...exceeds the 200MB limit..."

    // 第二层：按当前可用堆动态判定
    val availableHeapBytes = maxMemory() - totalMemory() + freeMemory()
    val requiredBytes = (fileSizeBytes * 1.34).toLong()
    val usableHeapBytes = (availableHeapBytes * 0.5).toLong()
    if (requiredBytes > usableHeapBytes) return "...needs about XMB but only YMB usable..."
    return null
}
```

**设计要点：**

| 决策 | 理由 |
|---|---|
| 两层判断（硬上限 + 动态） | 硬上限挡住 4GB 这种离谱输入；动态判断适配不同设备内存 |
| 用 `> ` 而非 `>=` | 恰好 200MB 应可读（边界正确） |
| 返回「原因字符串」而非布尔 | 错误信息带实际数字，便于区分是「文件太大」还是「设备内存太小」 |
| **不截断、不降级** | 符合 `AGENTS.md` 铁律：超限直接失败，不做静默处理 |
| 允许占 50% 可用堆 | 留一半余量给编码结果和调用链上的其他对象 |

### SAF 分支的特殊处理

`querySize(uri)` 返回 `Long?`，**可能为 null**。按铁律不能兜底，所以：

> **大小未知 → 拒绝，而不是放行。**

理由：放行会让 `readBytes()` 在大文件上直接 OOM 杀掉进程，比返回失败严重得多。同时移除了原来的 `querySize(uri) ?: bytes.size.toLong()` 兜底写法——它是先读进内存拿 `bytes.size` 再回填，等于「先爆了再说」。

### 为什么上限是 200MB

不是为了限制功能，是为了挡住离谱输入。真正的守门人是动态判断。业务场景（读图片、语音、视频附件）通常几 MB 到几十 MB，200MB 远高于正常需求，但足以挡住「读一个 4GB 文件」这类必然爆内存的操作。

### 验证

**硬上限边界（实测通过）：**

| 输入 | 结果 |
|---|---|
| 0 bytes | 可读 |
| 1KB | 可读 |
| 刚好 200MB | 可读 ✅ 边界正确 |
| 200MB + 1 | 拒绝 + 明确原因 ✅ |
| 1GB | 拒绝 + 明确原因 ✅ |

**动态内存判定（`-Xmx512m` 模拟普通手机，实测通过）：**

| 场景 | 文件 | 结果 |
|---|---|---|
| 内存充裕 | 1 / 10 / 30 / 50MB | 均可读（不误杀） |
| 已占 256MB 后 | 1 / 10 / 50MB | 仍可读 |
| 已占 256MB 后 | **100MB** | **拒绝**（需 134MB，只能给 126MB）|
| 已占 256MB 后 | **200MB** | **拒绝** |

结论：内存够就正常读（不卡插件功能），内存不够**提前返回失败**而不是让 App 崩掉。

---

## 验证方式说明

沙箱**无法本地完整构建 APK**（需外部 `subpack.zip` / `jniLibs.zip` / `libs.zip` + 原生模块 CMake 拉上游源码），
`kotlinc` 也只能编译**纯逻辑片段**（Android/Compose 依赖缺失）。

因此验证分三层：

| 层 | 手段 | 覆盖 |
|---|---|---|
| 逻辑正确性 | kotlinc 抽取纯逻辑片段 + 运行边界用例 | A2 / A3 / B3 的行为正确性 |
| 语法正确性 | 括号平衡校验（剔除字符串与注释后计数） | 全部 6 个文件 |
| 编译正确性 | CI `android-tests.yml` + `android-build.yml` | 全仓库真实编译 |

工具：`/tmp/kcc.sh`（kotlinc 封装，用 gradle 自带 `kotlin-compiler-embeddable-2.2.21`）。

---

## CI 记录

提交 `8a070e765bbd0a72e51e0023b5c70ce2ba903bda`，**双绿通过**：

| 流水线 | run id | 结果 |
|---|---|---|
| Android Tests | `36706852759` | ✅ success |
| Android Build | `36706852679` | ✅ success |

**产物**：`operit-android-230`（artifact id `11093351817`，436,662,776 字节 = 437MB）

---

## 未修复项（本轮范围外，已知存在）

| 编号 | 缺陷 | 说明 |
|---|---|---|
| A1 | `OkHttp3.js` multipart 传 `fields`，Kotlin 读 `form_data`/`files` | **对外接口契约**，需用户先确认方案 A/B 才能改，并同步 `okhttp.md` + `okhttp.d.ts` |
| B1 | TTS 枚举裸 `valueOf` 无错误信息 | 查证后确认：9 个枚举值从未改名，**从不触发**，属理论风险而非现实风险；STT 侧的 `runCatching` 兜底是上游原版代码，未触发过，保留不动 |
| B2 / B4 | OkHttp `Response` 未 close | 连接池泄漏 |
| B5 | 主线程 3 次 `runBlocking` 读 DataStore | 切页卡顿，慢设备逼近 ANR |
| C1–C6 | CI/构建配置问题 | `C3`（原生依赖未锁 SHA）最值得关注 |
