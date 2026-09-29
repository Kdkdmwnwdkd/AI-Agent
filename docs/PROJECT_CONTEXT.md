# 工作现场（防上下文丢失）

> 本目录记录跨会话必须保留的事实。新会话开始时先读这里。

---

## 🚨🚨 第 0 条：纠错纪律（用户 2026-09-29 指出，最优先）

**用户的批评原话：**
> "你之前改代码的时候还知道记录文件啥的，一到一个地方要重复改、在那纠错的时候，你就这忘那忘，上下文能力不行"

**这是真实存在的失败模式，必须靠纪律对抗，不能靠"我记得"。**

### 症状（我实际犯过的）

1. **纠错模式视野收窄**：一旦进入"改→挂→再改"循环，注意力全被**最后一个报错**吸走，
   已确认的事实被挤出视野。
2. **具体丢过的事实**：
   - 用户手机里**已经是 211**，我却按"还在 208"推演，输出整段"你必须卸 208" → **用户暴怒的直接原因**
   - 自己写在 CONTEXT.md 里的铁律自己不遵守
   - 同一个坑连踩两次（Kotlin 转义炸 `d99701d`，修完又在 `case...in` 上写出括号不平衡）
   - 用户明确说"是终端配置失败引发的"，我又绕回去讲签名
3. **不是"忘了查"，是根本没想着查** —— 当时觉得"我知道"。

### 硬性纪律（每次动手前执行）

```
① 动手前必读：/workspace/.workbuddy/CONTEXT.md 的「第 0 条 + 第七、八节」
② 回答用户前先自问：
   - 用户当前的实际状态是什么？（装的是哪个包？签名是什么？）
   - 用户已经明确否决过什么？（卸载！）
   - 用户当前真正要解决的是什么？（别绕到别的问题上）
③ 每修完一处，回到「原始诉求」核对一次：我还在解决用户要的问题吗？
④ 不靠数括号/眼看验证代码 —— 必须 kotlinc 编译 + javap 验证
⑤ 不确定的事实，先查再说，不许"觉得我知道"
```

### 用户的核心痛点（反复强调过）

- **讨厌被要求卸载**（已两次强烈爆发）—— 任何方案先保数据
- **讨厌重复劳动** —— 能一次做好的不要分两次
- **讨厌反复绕圈** —— 问 A 就答 A，别扯到 B
- **要求落盘** —— 结论写进文件，不要只依赖上下文

---
## 项目
- 仓库：`Kdkdmwnwdkd/AI-Agent`（Operit AI 的增强 Fork）
- 上游：`AAswordman/Operit`，Android 上开源的 AI Agent 平台，~48 万行 Kotlin，仅 ARM64，API 26+
- 本质：LLM + 200 个工具 → 真实操作设备 / 终端(Ubuntu via PRoot) / 浏览器 / 文件
- 构建门槛：需外部 `subpack.zip` / `jniLibs.zip` / `libs.zip` + `terminal` 子模块 → **沙箱内不可能本地跑完整构建，只能靠 CI**

## 仓库快照
- 位置：`/workspace/.workbuddy/repo-snapshot/`（已去 .git，58MB）
- 来源：ghproxy 匿名 clone 的 main 分支

## 关键 commit 时间线
```
ba9411c  09-27 15:31  4 个安全/重构 commit      ── Tests ❌ / Build ❌  exit 1
28d11eb  09-27 23:46  修清单合并 + 加密层单测   ── Tests ✅ / Build ❌  exit 143
3461d1f  09-28 09:04  CI 修 OOM（4g/2workers） ── ✅ 全绿  25分10秒
90c90777 09-28 10:09  调参（6g/4workers）      ── ✅ 全绿  19分44秒
3300f54d 09-28 11:20  重写工具描述（strings）  ── 被下一次覆盖
d0201c87 09-28 11:20  重写工具描述+续读提示    ── ✅ 全绿  Tests 8分26秒 / Build 29分06秒  ← 当前 main
```

## 通道（沙箱网络）
- **写**：`https://api.githubcopilot.com/mcp/`（GitHub MCP Server，JSON-RPC over HTTP，SSE 响应）
  - 45 个工具，含 `create_or_update_file` / `push_files` / `create_branch` / `create_pull_request`
  - **不含 Actions 工具**（`list_workflow_runs` 等均 unknown tool）
  - 更新文件必须带旧文件 `sha`
- **读 CI**：WebFetch + `https://api.github.com/...`
  - ⚠️ **WebFetch 有 15 分钟缓存**，查状态务必在 URL 加 `?probe=<时间戳>` 绕过
  - ⚠️ 普通 shell 里 curl 到 api.github.com 会 TLS 被切（DNS 劫持到 198.18.0.0/15）
- **PAT**：存在 `/tmp/.ghpat`（400 权限）。**注意：该 token 已明文暴露，需用户 revoke 重建**
- 辅助脚本：`/tmp/ghmcp.py`

## 待办 / 已知风险
1. **运行时验证未做**：`SecureStringCrypto` 加解密是否正确 —— CI 只保证能编译，加密坏了的症状是"连不上 LLM"
2. **功能保护性回退需复核**：SSRF 拦截 / 0.0.0.0 绑定 / receiver 签名权限 —— 报告说这些是 Operit 核心功能（连本地模型/局域网/Tasker），改动可能改废核心能力
3. **无分支保护**：Build 和 Tests 是独立 workflow，任一失败不阻塞推送 → `ba9411c` 的回归才能溜进 main
4. 未验证 `android-tests.yml` 的 Gradle 加固改动（已改文件，未推送）：
   - 加了 `GRADLE_BUILD_JVMARGS: '-Xmx4g ...'` 和 `GRADLE_BUILD_WORKERS: '4'` 到 env
   - 但 `Run JVM unit tests` 那行**还没改成使用这两个变量**

## 用户的核心诉求（2026-09-28）
- 更新那份滞后的《工作进展与后续计划报告.md》
- 清理仓库中**历史演进留下的冗余**（预留的大规模文件、重复文件）
  - 背景：Operit 从"AI 聊天平台"演进成"手机操作软件"，一路留下大量残留

## ⚠️ 项目铁律（用户 2026-09-28 明确提醒）
**聊天能力和 Agent/代码/控手机能力是并列的，不是替代关系。**

Operit 从纯聊天演进到"聊天 + 跑代码 + 控制手机"，但**聊天功能至今是其核心能力之一，不是历史残留**。

由此推导出清理判据：
- ✅ **该清**：同一东西写了两遍（重复声明/重复代码）、库已停止维护（需平滑替换）
- ❌ **不该碰**：任何聊天侧功能 —— 对话、语音(STT/TTS)、音乐播放、角色卡、记忆、模型接入
- ❌ **禁止的思维**："这是聊天时代的东西所以老了" —— 这个判据是错的

举例：`exoplayer → media3` 可以换，但理由是"该库已停止维护"，**不是**"聊天的东西过时了"；
且替换必须保证语音/音乐播放/连续对话等功能不退化。

## 依赖清理方案（已与用户确认）
1. 清重复声明 + 换废弃库 + 建自动化护栏（Renovate/Dependabot + CI 检查 + 分支保护）
2. **逐项推、每项等 CI**，不批量推
3. 先出清单 → 用户确认 → 再推

## 已知依赖问题（第 1 批，清单已出待确认）
- `androidx.core:core-ktx`：死定义（`coreKtxVersion`=1.12.0 全仓零引用，实测验证）
- `org.apache.commons:commons-compress`：两处真实引用，`-v2` 版本反而更低（1.24.0 < 1.25.0）
- 清单文件：`/workspace/dependency-cleanup/01-去重清单.md`

## 🔴 重大发现（第 2 批盘点时确认，2026-09-28）
**FFmpegKit 已死，但仓库已自建 AAR 绕开 —— 真正的问题是构建供应链。**

Maven Central 实证：
```
https://repo1.maven.org/maven2/com/arthenica/
→ 只剩 smart-exception-common / -java / -java9 / -logback
  所有 ffmpeg-kit-* 目录已消失（ffmpeg-kit-full/ → 404）
```

仓库现状（`app/build.gradle.kts`）：
- L155 `val ffmpegKitLocalAar = file("libs/ffmpeg-kit-local.aar")`
- L598 `implementation(files("libs/ffmpeg-kit-local.aar"))` + L599-600 `smart-exception-*:0.2.1`（后者**仍在 Maven，安全**）
- 有 `verifyExternallyBuiltNativeLibraries` 校验 AAR 内 10 个 arm64 .so

**构建依赖 Google Drive 不透明归档**（`ci/script/download_android_dependencies.sh`）：
```
libs.zip     ← 1Va1os7PRpCF3xtTwfx5kO11D7eIAvARG  → app/libs
subpack.zip  ← 1SQs_dVPD6ldvwteqoUVjvBLjTWvr5Fpv  → app/src/main/assets/subpack
jniLibs.zip  ← 1-W4fjjUwoShnB8Rh9RT5Gl8sHiGyQUaM  → app/src/main/jniLibs
（gdown 下载，prepare_android_dependencies.py 解压校验）
```
→ **构建不可复现**：file-id 不在版本控制、无校验和、AAR 内 FFmpeg 版本未知；FFmpeg 侧还有 Via-LA 专利尾巴。

可选路线（待用户决策）：A 换 fork `dev.ffmpegkit-maintained`（改 group ID 即可）／B 迁 media3（会丢失任意命令能力）／**C 先锁现状加护栏（我建议起步）**

## 后续批次（未开始）
- 第 3 批：废弃依赖替换（exoplayer 2.19.1→media3、tensorflow-lite 2.10、glide+coil 双图片库并存等）
- 第 4 批：护栏（renovate.json + CI 依赖检查 + 分支保护）
- 工具盘点：✅ **已完成**，见 `/workspace/dependency-cleanup/02-工具盘点表.md`
  - 183 个静态注册工具 / 20 个实现类，逐块解析 + 描述文案核对
  - 结论：**没有一个工具该因"属于聊天时代"被删**
  - 功能重复仅 1 处（FFmpeg 三件套能力重叠，但建议保留 convert 的结构化参数）
  - 蓝牙 14 个 / 音乐 8 个 / 聊天 15 个 = **明确不动**

## 🔥 工具能力升级评估（第 2.5 批，用户 2026-09-28 指出）
见 `/workspace/dependency-cleanup/03-工具能力升级评估.md`

**用户原话**："25年、26年这两年 AI 发展太快，有些工具该升级了" —— 指的是**能力代差**，不是版本号新旧。这个判断是对的。

### 两个 P0 发现
1. **183 个工具全量暴露**。行业共识：工具数 >~15 选择准确率明显下降（OpenAI 指南 / 177,436 个 MCP 工具研究）。
   **⚡ 项目已实现渐进式披露，但只在本地小模型启用**：
   `core/tools/climode/CliToolModeSupport.kt` 的 `ToolExposureMode.resolve()` ——
   LMSTUDIO/OLLAMA/OPENAI_LOCAL/MNN/LLAMA_CPP → `CLI`（只暴露 search+proxy 2 个工具）；
   其他所有云端模型 → `FULL`（183 个全暴露）。
   → **建议扩展 CLI 到云端模型（或给开关）**。这是最高收益项，但属行为变更需用户确认。

2. **43 个工具的描述生成器输出空/无效**（占 23%），实测：
   - 走 `R.string.*`：83 个 ✅
   - 硬编码英文：49 个 ⚠️
   - **空/无效：43 个** 🔴 ← 含 `browser_*` 一大半、`music_*` 全部
   行业共识："Most tool failures are description failures." 补描述**零风险**。

### 其他
- `read_file` 家族 5 个 → 建议合并为 1 个带 offset/limit
- `ToolResult` 缺 `retryable` 字段（能消除大量无效重试循环）
- 统计脚本产物：`/tmp/tools_final.json`、`/tmp/desc_stat.json`

## 🎯 代码能力短板根因（第 2.6 批，用户再次强调"读全部、慢、容易错"）
见 `/workspace/dependency-cleanup/04-代码能力根因诊断.md`

**用户原话**："他这个代码能力不够强啊，有些老的工具不太行……每一次读的话就是把全部过去读一遍就会效果很慢，况且还容易错"

### 已定位到确切代码位置
1. **4 个读取工具描述无法区分**（`ToolRegistration.kt` L1948/1960/1982/1994）：
   `read_file`="读取文件: %1$s"、`read_file_part`="读取文件 (%1$s): %2$s"、
   `read_file_full`="读取完整文件内容: %1$s" —— **三个都以"读取文件"开头**，模型无法判断该用哪个。
2. **32KB 截断导致反复重读**：`ToolExecutionLimits.MAX_FILE_READ_BYTES = 32_000`。
   1000 行 Kotlin ≈ 35-45KB → 一次读不完 → 模型换工具再读 → 反复烧上下文。
   `read_file_part` 默认 200 行 / 次。
3. **没有分页引导**：`read_file_part` 实现里**已返回 `totalLines`**（L1682 附近），
   但**描述里从不告诉模型**这是分页工具、下一步该传什么参数。
   全模块搜 `has_more`/`next_offset`/`cursor` → 工具层零结果（聊天侧 `WebChatHttpBridge.kt` 反而有分页）。

### 修复优先级（建议先做 1+2，零逻辑改动）
1. 🔴 重写 4 个读取工具描述（只改 `strings.xml`）← 最高性价比
2. 🔴 工具结果末尾加"续读提示"（用已有的 totalLines）
3. 🟡 `ToolResult` 加 `retryable` 字段
4. 🟡 重写 `grep_code` / `grep_context` 描述（讲清"何时用哪个"）
5. 🟢 `read_file` 家族 4→1 合并（**建议押后**，回归面大）

### ✅ 已完成并验证（commit `d0201c87`，双绿）
- **修复 1**：`strings.xml` 重写 8 条描述
  - `toolreg_read_file_desc`：加"【小文件优先】…最多 32000 字节（约 800 行）"、
    截断时指向 `read_file_part`、明确"不要重复调用本工具"
  - `toolreg_read_file_part_desc`：加"【读大文件首选，不会截断】"+ 教会模型
    `start_line = 上次 end_line + 1`、首次可只传 path 默认 200 行
  - `toolreg_read_file_full_desc`：加"【谨慎使用】…会占用大量上下文"
  - `toolreg_read_file_binary_desc` / `list_files` / `grep_code`(x2) / `grep_context`：
    补"何时用哪个"的选择依据
- **修复 2**：`StandardFileSystemTools.readFilePart` 返回末尾追加续读提示
  - 输出 `[已读 X-Y 行，共 N 行，剩余 M 行]` + `[续读请再次调用 read_file_part，参数 path="…", start_line=…, end_line=…]`
  - 已处理边界：`totalLines<=0` 输出 `[文件为空]`；读到底输出 `[已读到文件末尾，无需续读]`
  - 改动仅在 Android 分支（Linux/SAF 委托是前置 early-return，不受影响）
  - 用 `returnedEndLine` 替换原 `minOf(endIndex, totalLines)`，语义等价
  - 已验证：括号平衡 711/711、1895/1895；5 个场景逻辑模拟输出正确
- CI 证据：`d0201c870d` → Android Tests ✅ (8分26秒) / Android Build ✅ (29分06秒)

### 待办（用户已同意"先搞完这些，然后再看工具"）
- 下一步：修复 3（`retryable`）、修复 4（grep 已含在 1 中）、再看第 2.5 批的 CLI 模式扩展
- 第 1 批去重三项仍在等用户确认

---

# 🔥🔥 第二阶段：terminal 收编 + 签名固定 + pnpm 修复（2026-09-29）

> 本节是**最新状态**，优先级高于以上所有内容。
> 以上第 2.5/2.6 批的分析是"待办池"，本节是"正在做的事"。

## 一、核心提交时间线（都在 `Kdkdmwnwdkd/AI-Agent` main）

```
dc26fed  09-29 18:23  docs: 排障笔记（仓库内文档，次要）
b9fef96  09-29 18:22  fix(terminal): pipx/pnpm PATH 注入 + 校验   ← 待 CI 验证
e080d51  09-29 18:17  fix(terminal): 修正 Kotlin 转义               ← Tests ✅
d99701d  09-29 18:02  fix(terminal): pnpm 全局目录                 ── ❌ 双失败（已被 e080d51 修）
a9de84b  09-29 17:45  fix(ci): Android Tests 补 keystore 步骤      ── ✅
510bcd5  09-29 17:13  fix(signing): getByName 覆盖 AGP debug 配置   ── ✅ 出 211
4a33cad  09-29 17:00  fix(signing): create("debug")                ── ❌ 重名报错（已被 510bcd5 修）
db2c308  09-29 16:17  refactor(terminal): 收编 terminal 为自有目录  ── ✅ 出 208
```

## 二、terminal 收编（已完成）

- 原为 git submodule，pin `045868988e`（**比上游 master `e4442bc6a0` 更新**，含 "expose PTY child pid"）
- 已收编进主仓库自有目录 `terminal/`，68 个文件 SHA1 68/68 校验通过
- `.gitmodules` 移除 terminal 段；两个 workflow 移除 submodule 初始化
- Gradle 侧**零改动**，CI 双绿（#191 Tests + #209 Build）

## 三、pnpm 链路（三层问题，逐层剥开）

### 3.1 命令执行机制 —— 理解一切的前提
```kotlin
// TerminalEnv.kt:54
fun onSetup(commands: List<String>) {
    val fullCommand = commands.joinToString(separator = " && ")
    terminalManager.sendCommand(fullCommand)
}
// TerminalManager.kt:366 → writeInputToKernel(session, "$command\r", "command")
```
**不是 `bash -c`**，是把整条 `&&` 链一次性写进 PTY stdin。
- 整条链由**同一个 shell 进程**解析 → 链中的 `export` **对后续命令有效**
- `&&` 链**任一环非 0 则后续全不执行** → 易失败的都要带 `|| true`

### 3.2 三层问题与状态

| 层 | 症状 | 根因 | 修法 | 状态 |
|---|---|---|---|---|
| 1 | `bash: pnpm: command not found` | npm 7+ 拦截依赖包 preinstall 脚本，只解压不生成入口 | `npm install -g --allow-scripts=pnpm pnpm` | ✅ 真机验证 `[OK] pnpm 安装成功` |
| 2 | `ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH` | pnpm 全局 bin 在 `~/.local/share/pnpm/bin`（**不同于 npm 的 `npm prefix -g/bin`**），默认不在 PATH | `pnpm setup` + `export PNPM_HOME/PATH` | ✅ 代码已修（`b9fef96`） |
| 3 | uv/uvx 装了不可用 | `pipx ensurepath` 同样只改配置 | `export PATH="$HOME/.local/bin:$PATH"` | ✅ 代码已修（`b9fef96`） |

### 3.3 ⚠️ 核心陷阱：持久化 ≠ 当前会话生效
**这是之前一直修不好的真正原因。**

`pnpm setup` / `pipx ensurepath` 都是**只写 shell 配置文件**：
- 对**新开的**会话有效 ✅
- 对**当前正在跑的**会话无效 ❌

而终端会话在用户点「开始配置」**之前就启动了**。所以必须两步都做：

| 步骤 | 作用域 | 必要性 |
|---|---|---|
| `pnpm setup` | 持久化（新会话） | 必须，否则重启终端又坏 |
| `export ...` | 当前会话 | 必须，否则本次配置后续 `pnpm add -g` 必失败 |

### 3.4 ⚠️⚠️ Kotlin 字符串模板陷阱（炸过一次 CI）
**要输出字面 `$`，用 `${'$'}`。绝不在 `$` 前加反斜杠。**

```kotlin
commands.add("export PNPM_HOME=\"\\$HOME/...\"")        // ❌ unresolved reference 'HOME' 编译失败
commands.add("export PNPM_HOME=\"\${'$'}HOME/...\"")    // ❌ 能编译但多输出一个字面 \
commands.add("export PNPM_HOME=\"${'$'}HOME/...\"")     // ✅ 正确
```
→ `d99701d` 就是因为第一种写法，导致 `android-build` + `android-tests` 双失败（#212 / #195）。

**验证方法（不能靠数括号，中文注释里的 `(` `)` `$` 会污染统计）：**
```bash
KH=/root/.sdkman/candidates/gradle/9.3.0/lib
CP="$KH/kotlin-compiler-embeddable-2.2.21.jar:$KH/kotlin-stdlib-2.2.21.jar:$KH/kotlin-reflect-2.2.21.jar:$KH/kotlin-script-runtime-2.2.21.jar:$KH/kotlin-daemon-embeddable-2.2.21.jar:<coroutines>/<annotations>"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home /tmp/kh -d out T.kt
javap -c -p -constants out/TKt.class     # 读常量池确认最终 shell 字符串
```
已实测生成结果正确：
```
export PNPM_HOME="$HOME/.local/share/pnpm"
export PATH="$PNPM_HOME/bin:$PATH"
```

## 四、APK 签名固定（已完成，211 已验证）

### 4.1 根因（最隐蔽的坑）
`app/build.gradle.kts` 的 `signingConfigs` **原本没声明 `debug`** →
`buildTypes.debug` 走 **AGP 内置 debug 配置**，其 `storeFile` **不是** `~/.android/debug.keystore`，
是 AGP 自管路径；**缺失时静默生成随机密钥继续构建**。

→ 每次 CI 出的包签名都不同 → 「安装包无效或不兼容」→ **构建日志完全正常，问题被彻底掩盖**。

### 4.2 修法
```kotlin
signingConfigs {
    getByName("debug") {          // ✅ 必须 getByName；用 create 会报重名：
        // Cannot add a SigningConfig with name 'debug' as a SigningConfig
        // with that name already exists.
        val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (!debugKeystore.exists()) throw GradleException("找不到固定的 debug keystore...")
        storeFile = debugKeystore
        storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
    }
}
```

### 4.3 两个 workflow 必须同步
`android-build.yml` **和** `android-tests.yml` 都要有 `Setup fixed debug keystore` 步骤。
（曾只加了 build 版 → `Android Tests` 因「文件不存在即失败」挂掉，CI #193）

### 4.4 固定钥匙信息
| 项 | 值 |
|---|---|
| 文件 | `ci/signing/debug-keystore.b64`（base64 3602 → 解码 2666 字节） |
| 别名 / 密码 | `androiddebugkey` / `android` |
| 证书 SHA1 | `aaafdab58bcd76052c2acf4d7ef7850436d98545` |
| 证书 SHA256 | `cc69b50a001938f92f2da449a428468f409953c64a50efc94d27b3491de8bf43` |
| 证书 DER 长度 | **792 字节**（随机密钥是 744，可作辅助判据） |

### 4.5 验签方法（别用错）
**❌ 不可**：拿 APK 里证书直接和 keystore 文件比字节。
keystore 是「私钥+证书」，APK 里是 Android **重新编码后**的证书，**天然不同**（曾因此误判 209）。

**✅ 方法一**：`keytool -printcert -jarfile app.apk`
**✅ 方法二（沙箱无 keytool 时）**：纯 Python 解析 APK Signing Block
（v2 id = `0x7109871a`；结构 `[u64 size][pairs][u64 size]['APK Sig Block 42']`；
`signer = signedData | signatures | publicKey`，`signedData = digests | certificates | attrs`）

### 4.6 实测三份 APK
| APK | 提交 | DER | 证书 SHA1 | 判定 |
|---|---|---|---|---|
| 208 | `db2c308` | 744 | `687e67b8…` | ❌ 随机 |
| 209 | 早期 | 744 | `41cae53e…` | ❌ 随机 |
| **211** | `510bcd5` | **792** | **`aaafdab5…`** | ✅ **= 固定钥匙** |

### 4.7 物理事实（必须向用户讲清）
**签名不同的两个 APK，Android 不允许互相覆盖，必须卸载重装。**
208/209 的随机密钥**已在 CI 临时容器销毁，不可找回**（2048 位 RSA 无法伪造）。

→ **208 → 211 必须卸载一次**；**211 之后所有包签名一致，永久可覆盖**。

## 五、交付物与产物堆（用户已抱怨目录太乱）

| 文件 | 说明 |
|---|---|
| `/workspace/Operit-3.3.0-debug-208.apk` | 427MB，签名 `687e67b8`（随机） |
| `/workspace/Operit-3.3.0-debug-209.apk` | 签名 `41cae53e`（随机） |
| `/workspace/Operit-3.3.0-debug-211.apk` | 427MB，签名 `aaafdab5` ✅ **用户手机当前安装的就是这个** |
| `/workspace/operit-debug-keystore.zip` | 固定钥匙备份（用户已自己保存） |
| `/workspace/terminal-takeover-report.md` | terminal 收编完整报告 |
| `/workspace/.workbuddy/CONTEXT.md` | **本文件** |
| 仓库 `docs/TERMINAL_AND_SIGNING_NOTES.md` | 仓库内排障笔记（给未来开发者） |

⚠️ `/workspace/` 累计 2.3GB / 21 个条目，含大量中间产物（`wsrc/`、`operit-android-208.zip`、
`apk-out/`、`ci-fix/`、`dependency-cleanup/` 等）。**用户要求清理。**

## 六、网络通道更新（2026-09-29 实测）

### gh-proxy 能力边界（沙箱无法直连 GitHub）
| 能力 | 支持 | 备注 |
|---|---|---|
| 代理 codeload tarball | ✅ | |
| 代理需鉴権的 artifact 接口 | ✅ | 服务端自带凭据 |
| 转发 git receive-pack | ✅ | `git push` 可用，62MB 大文件实测 OK |
| 转发客户端 `Authorization` 头 | ❌ | 返回 77 字节 `Web page content is not allowed` |
| 代理 `/actions/jobs/{id}/logs` | ❌ | **拿不到 CI 日志，需人工贴** |
| 代理 `api.github.com` 带 token | ❌ | 一律 403 |

### 可用姿势
```bash
# git 推送（可推大文件）
git remote set-url origin \
  "https://x-access-token:${PAT}@gh-proxy.com/https://github.com/OWNER/REPO.git"
# API 读（不含敏感头时可用）
curl -H "Authorization: Bearer $PAT" \
  "https://gh-proxy.com/https://api.github.com/repos/OWNER/REPO/actions/runs?per_page=8"
```
- ⚠️ blob API 传大文件不可行（base64 后 85MB 超时；octet-stream 报 400）
- ⚠️ PAT 在 `/tmp/.ghpat`，**已明文暴露，需用户 revoke 重建**
- 沙箱直连 `api.github.com` = `000`（DNS 劫持）

## 七、⚠️ 交接铁律（违反会直接炸）

1. **不要凭猜测改 Kotlin 代码** —— 改完必须用 kotlinc 编译 + javap 验证
2. **不要只改一个 workflow** —— tests 和 build 必须同步
3. **绝不轻易建议用户卸载** —— 用户已两次强烈爆发，除非同时给出保数据方案
4. **用户手机已是 211（签名正确）** —— 后续新包可直接覆盖安装，不必再卸
5. **不要重复问已确认过的事** —— 见「八、用户已明确的诉求」

## 八、用户已明确的诉求（不要再问）

- **备份统一化**（下一件大事）：目前人物 / 技能 / 聊天记录**各有独立备份按钮**，
  要逐个点。用户要求做成**一个入口全量打包 + 一键还原**。**排在签名线之后做。**
- **改应用名 / 换图标**：签名稳定后做
- **数据不能丢**：用户对「卸载」极度抵触，任何方案必须先保证数据安全
- **产物目录要清理**：`/workspace/` 堆了 2.3G / 21 项中间产物
- **记忆要落盘**：用户明确要求把结论写进文件（就是本文件），不要只依赖上下文

## 九、当前待办（按优先级）

- [x] **`b9fef96` CI 验证** —— ✅ 双绿
- [x] 出包含全部终端修复的新 APK —— ✅ 211，用户已覆盖安装
- [x] 真机验证终端配置 —— ✅ 用户 5 张截图全绿（uv/python3/pip3/java/gradle/rustc/git/ffmpeg）
- [x] 真机验证签名固定 —— ✅「这个的话能直接覆盖了」
- [ ] **🔴 全量改动真机验证**（用户 09-29 最后一个问题）—— 见第十节
- [ ] **统一备份功能开发**（人物/技能/聊天记录一个入口）
- [ ] 清理 `/workspace/` 中间产物
- [ ] 改应用名 / 换图标
- [ ] 提醒用户 revoke 重建 `/tmp/.ghpat`

---

# 📋 第十节：全量改动真机验证清单（2026-09-29 用户最后指令）

> **用户原话**：「改了那么多就没有其他要验证吗？这样子就完成了。你看一下那些对话的产物啊，那个有些文件」
>
> **完整版**：仓库 `docs/VERIFICATION_CHECKLIST.md` ／ 资料库「10-全量改动真机验证清单」`uoG5JNAzhyQmhQF4GvZdL9`

## 关键事实：两天共 154 个提交

```
09-27  39 条
09-28  77 条
09-29  38 条
─────  ─────
合计  154 条   （仓库总历史 1970 commits，已 --unshallow 取全）
```

## ✅ 已真机验证（5 项，用户已确认）

签名固定 / 终端工具链 / 配置入口 / 权限系统 / pnpm 安装

## 🔴 仅过 CI、真机未验（6 类）

| 类 | 提交 | 涉及功能 | 风险 |
|---|---|---|---|
| **A. Media3 迁移** | `7137a1e`…`68576b7b` + 9 补漏 | 音乐/背景视频/聊天音视频附件/Markdown 音视频 | **最高**（纯 API 迁移，CI 只证编译） |
| **B. Coil 2→3** | `91a0e9d5` `d753b47a` | 所有图片：头像/角色卡/聊天图/背景 | 高（breaking change，已踩过 2 处误用） |
| **C. 11 项死依赖移除** | `725cc5d0` `c474b517` | glide/android.gif/apk.parser/zip4j/renderx/tensorflow-lite… | 高（编译过≠功能在） |
| **D. 工具描述重写** | 约 30 条，`d0201c87` 起 | 213 条 `toolreg_*`，模型选工具行为 | 中（需真机对话观察） |
| **E. retryable 字段** | `9daec219` `acb0dab5` `18fbee2a` | 工具失败重试策略 | 中 |
| **F. AndroidX 7 项升级** | `bd265937` `bdbc2a20` | `onNewIntent` → 分享/通知回 App | 中（漏了就是闪退） |

> 💡 **A+B+C 都动「图片与媒体显示」链路，建议一起验（翻几个页面即可）。**

## 建议验证顺序（风险 × 成本）

1. 图片显示（B+C 合并）—— 成本最低收益最高
2. 音视频播放（A，5 项）
3. 分享/通知回 App（F，3 项）—— 30 秒
4. 跟 AI 聊几轮（D+E）—— 正常用即可，注意观察选工具行为
5. 设置页扫一眼空文本残留

## 工具描述覆盖实测（本次核对）

- 187 个注册块 → **182 个走 `s(R.string.*)`**，5 个真·动态（含运行时参数）
- **硬编码英文描述：0 个** ✅
- `strings.xml` 共 **213 条 `toolreg_*`**
- CI 护栏：`ci/script/check_tool_descriptions.py`，PR 时硬编码即失败

## 遗留技术债（仍在）

- `terminal/src/.../SetupScreen.kt:695,718` —— `checkPackageInstalled` 仍是
  **裸子串判定**（`contains("not found")` + `isNotBlank()`），有误判风险。
  同类的 `DemoStateManager` 已改用 `TerminalProbe.isRunnable()`，**这里没跟着改**。
  建议下一轮统一替换。


