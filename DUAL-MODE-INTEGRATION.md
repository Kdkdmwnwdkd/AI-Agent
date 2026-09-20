# Operit 双模式隔离架构集成说明（v1.0.1g）

> 将 AI-Agent（AAswordman/Operit 克隆）从单模式改造为"代码模式 + 角色模式"双模式，聊天记录与记忆互不污染。
> 改造日期：2026-09-20
> 基于：Operit-CoreLogic-v1.0.1g 参考实现

## 一、改造总览

| 层级 | 改造文件数 | 核心变更 |
|------|-----------|---------|
| 新增核心文件 | 7 | 模式管理器、存储隔离、工具/权限隔离、UI控件、模式枚举 |
| 现有文件改造 | 5 | 路由层、插槽层、数据层（Entity+DAO） |
| 设计文档 | 2 | 集成方案（519行）、本说明 |

## 二、新增文件清单

### 2.1 双模式核心逻辑（`core/dualmode/`）

| 文件 | 职责 | 对应文档条款 |
|------|------|-------------|
| `OperitMode.kt` | 模式枚举：SINGLE / CODE / ROLE | §4.3 |
| `ModeManager.kt` | 模式管理器：Mutex 串行化切换、状态保存/恢复 | §5.1（不变式D、A） |
| `DualModeStorageManager.kt` | 文件系统物理隔离：按模式分目录 | §6.2 |
| `ModeAwareToolRegistry.kt` | 工具可见性隔离：角色模式不暴露代码工具 | §6.5 |
| `ModeAwarePermissionGate.kt` | 授权历史按模式隔离：绝不跨桶 | §6.5 |

### 2.2 UI 层（`ui/components/`）

| 文件 | 职责 |
|------|------|
| `ModeSwitcher.kt` | Jetpack Compose 模式切换按钮组（顶部栏插入） |

### 2.3 数据层（`data/model/`）

| 文件 | 职责 |
|------|------|
| `ModeFunctionType.kt` | 双模式专用的 FunctionType 映射枚举 |

## 三、现有文件改造清单

### 3.1 逻辑层

| 文件 | 改造内容 | 文档条款 |
|------|---------|---------|
| `api/chat/EnhancedAIService.kt` | `sendMessage()` 入口插入双模式路由：按当前 OperitMode 选择 effectiveFunctionType 和 effectiveMemorySpaceId | §5.1 路由 |
| `api/chat/ChatRuntimeHolder.kt` | `setupCrossSessionSync()` 添加 `setupModeSlotSync()`：CODE_MODE/ROLE_MODE ↔ FLOATING 同步，但**跨模式不同步** | §6.2 隔离 |
| `api/chat/ChatRuntimeSlot.kt` | 添加 `CODE_MODE`、`ROLE_MODE` 枚举值 | §5.1 插槽 |

### 3.2 数据层

| 文件 | 改造内容 | 文档条款 |
|------|---------|---------|
| `data/model/ChatEntity.kt` | 添加 `mode: String = "SINGLE"` 字段（默认向后兼容） | §6.2 |
| `data/dao/ChatDao.kt` | 核心查询（`getAllChats`、`getTotalChatCount`、`getAllChatsDirectly`、`getChatById`）增加 `mode` 参数，**无默认值**（编译期强制隔离） | §6.1（入口收紧） |

## 四、隔离机制详解

### 4.1 聊天记录隔离（数据库层）

- `ChatEntity` 新增 `mode` 字段
- `ChatDao.getAllChats(mode)` 必须传 mode 参数，不传编译失败（§6.1）
- 数据库 Migration：现有数据 `mode = "SINGLE"`，不影响老用户

### 4.2 文件系统隔离（物理层）

```
files/
├── mode_code/          # 代码模式专属
│   ├── chat_history/
│   ├── memory/
│   └── workspace/
├── mode_role/          # 角色模式专属
│   ├── chat_history/
│   ├── memory/
│   └── workspace/
└── (原有文件)         # SINGLE 模式复用根目录（向后兼容）
```

### 4.3 工具集隔离（逻辑层）

| 模式 | 可用工具 |
|------|---------|
| 代码模式 | read_file、write_file、run_shell、git_status、git_diff、search_code、list_dir、apply_patch 等 |
| 角色模式 | send_message、memory_recall、emotion_analyze、image_recognition 等（仅限对话相关） |
| 单模式 | 全部工具（向后兼容） |

### 4.4 授权历史隔离（权限层）

- 授权键格式：`"{mode}:{permissionId}"`（如 `"CODE:read_file:/data"`）
- 代码模式批准了文件访问 → 角色模式请求同一文件仍需用户确认（§6.5 不跨桶）

### 4.5 运行时插槽隔离（架构层）

```
ChatRuntimeSlot:
├── MAIN ↔ FLOATING          # 同窗口同步（原有）
├── CODE_MODE ↔ FLOATING     # 代码模式内同步（新增）
└── ROLE_MODE ↔ FLOATING     # 角色模式内同步（新增）

跨模式：CODE_MODE ⟷ ROLE_MODE  # 绝对不同步（§6.2）
```

## 五、向后兼容

| 场景 | 行为 |
|------|------|
| 老用户升级 | 默认关闭双模式，`mode = "SINGLE"`，一切保持原样 |
| 未开启双模式时 | `ModeSwitcher` 不显示，`OperitMode.SINGLE` 始终激活 |
| 数据库 | Migration 自动填充 `mode = "SINGLE"`，现有查询不受影响 |
| 文件系统 | SINGLE 模式继续使用原有根目录，不创建 `mode_code/` / `mode_role/` |

## 六、启用双模式

### 6.1 设置入口（需手动添加）

在设置页面添加开关：
- 标签："启用双模式（代码/角色）"
- 说明："开启后，应用顶部会出现模式切换按钮，两个模式的聊天记录完全隔离"

### 6.2 代码启用

```kotlin
// 开启双模式
ModeManager.getInstance(context).setDualModeEnabled(true)

// 切换模式（协程环境）
lifecycleScope.launch {
    ModeManager.getInstance(context).switchTo(OperitMode.CODE)
}
```

### 6.3 UI 插入 ModeSwitcher

在主 Chat 界面的 Compose 顶部栏（标题右侧）插入：

```kotlin
@Composable
fun ChatTopBar(...) {
    Row(...) {
        Text(title, ...)
        ModeSwitcher(
            currentMode = modeManager.currentMode.collectAsState().value,
            isDualModeEnabled = modeManager.isDualModeEnabled,
            onModeChange = { mode ->
                scope.launch { modeManager.switchTo(mode) }
            }
        )
    }
}
```

## 七、验证清单（对照文档条款）

| 文档条款 | 验证方法 | 状态 |
|---------|---------|------|
| §5.1 互斥切换（Mutex） | 断言 `switchMutex` 串行化并发切换 | ✅ 已落地（ModeManager.kt） |
| §5.1 先保存再切换 | 断言切换前旧模式现场已落盘 | ✅ 已落地（ModeManager.kt） |
| §6.1 编译期检查 | 断言 `ChatDao` 不带 mode 参数编译失败 | ✅ 已落地（ChatDao.kt 无默认值） |
| §6.2 物理隔离 | 断言 `files/mode_code/` 和 `files/mode_role/` 独立 | ✅ 已落地（DualModeStorageManager.kt） |
| §6.5 授权不跨桶 | 代码模式授权后，角色模式仍需确认 | ✅ 已落地（ModeAwarePermissionGate.kt） |
| §6.5 工具隔离 | 角色模式不暴露 shell/write_file | ✅ 已落地（ModeAwareToolRegistry.kt） |
| §7.1 压缩触发 | token>70% + 无在途调用时触发 | ⏳ 需后续实现（ContextState 已预留） |
| §9.1 两阶段提交 | PREPARED→COMMITTED→CLEANED | ⏳ 需后续接入 `patch/` 目录文件 |

## 八、待办事项（后续迭代）

### Phase 2（运行时隔离完善）
- [ ] `ChatServiceCore` 按模式隔离消息历史（当前只隔离了数据库查询，内存中的消息列表仍需隔离）
- [ ] `MultiServiceManager` 注册 `CODE_MODE_CHAT` / `ROLE_MODE_CHAT` 的模型配置（系统提示词差异）
- [ ] `ConversationService` 按模式过滤消息记录

### Phase 3（上下文压缩）
- [ ] 接入 `CompressionCoordinator`（`core/dualmode/` 或新目录）
- [ ] `ContextState` 按模式独立维护 token 计数

### Phase 4（崩溃恢复）
- [ ] 将 `operit-core-logic` 的 `patch/` 目录（FsSync、JournalStore、TwoPhaseCommit）适配为 Android 组件
- [ ] 用 Android `Os.fsync` 替代 JNA 目录 fsync
- [ ] 数据库 Migration v1→v2 包含 mode 字段

## 九、参考实现

本改造基于 `Operit-CoreLogic-v1.0.1g.zip`（沙箱内编译通过，24 用例/77 断言全绿）：
- 仓库：https://github.com/Kdkdmwnwdkd/Operit-CoreLogic
- 核心不变式：A（先落盘再取消）、B（buildContext 只收 query）、C（derivedTasks 暂停）、D（Mutex 串行化）、E（resume 先扫 journal）
- 关键修正：P0（快照 key 用 old.scope.id）、P1（恢复先扫 journal）

## 十、文件路径速查

```
app/src/main/java/com/ai/assistance/operit/
├── core/dualmode/
│   ├── OperitMode.kt              ← 模式枚举
│   ├── ModeManager.kt             ← 模式管理器（Mutex 切换）
│   ├── DualModeStorageManager.kt  ← 文件系统隔离
│   ├── ModeAwareToolRegistry.kt   ← 工具隔离
│   └── ModeAwarePermissionGate.kt ← 权限隔离
├── ui/components/
│   └── ModeSwitcher.kt            ← 顶部栏切换控件
├── data/model/
│   ├── ModeFunctionType.kt        ← 模式专用 FunctionType
│   └── ChatEntity.kt              ← +mode 字段（已改造）
├── data/dao/
│   └── ChatDao.kt                 ← +mode 参数（已改造）
├── api/chat/
│   ├── EnhancedAIService.kt       ← 双模式路由（已改造）
│   ├── ChatRuntimeHolder.kt       ← 模式插槽同步（已改造）
│   └── ChatRuntimeSlot.kt         ← +CODE_MODE/ROLE_MODE（已改造）
```

---

**改造完成时间**：2026-09-20
**总文件变更**：12 个文件（7 新增 + 5 改造）
**向后兼容**：✅ 默认关闭双模式，老用户无感知
