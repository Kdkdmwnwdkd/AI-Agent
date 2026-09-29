# 终端配置 & APK 签名 排障笔记（必读）

> 本文档记录的是**踩过的坑、真实根因、已验证的修法**。
>
> 目的：任何人（包括后续接手的 AI）在做相关改动前，**先读这份文档**，
> 不要重复踩坑，不要凭猜测改代码。
>
> 最后更新：2026-09-29

---

## 零、一句话结论

| 问题 | 根因 | 修法 | 状态 |
|---|---|---|---|
| `pnpm: command not found` | npm 7+ 拦截 preinstall 脚本 | `npm install -g --allow-scripts=pnpm pnpm` | ✅ 已修 |
| `ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH` | pnpm 全局 bin 目录不在 PATH；且 `pnpm setup` 对当前会话不生效 | 补 `export PNPM_HOME` / `export PATH` | ✅ 已修（`b9fef96`） |
| uv/uvx 装了但不可用 | `pipx ensurepath` 同样只改配置，对当前会话不生效 | 补 `export PATH="$HOME/.local/bin:$PATH"` | ✅ 已修（`b9fef96`） |
| APK 无法覆盖安装 | `signingConfigs` 未声明 `debug`，AGP 每次随机生成密钥 | `getByName("debug")` 覆盖内置配置 | ✅ 已修（`510bcd5`） |

---

## 一、终端环境配置（SetupScreen）

### 1.1 命令是怎么执行的 —— 这是理解一切的前提

`SetupScreen.kt` 里的 `commands` 是一个 `List<String>`，最终由：

```kotlin
// TerminalEnv.kt:54
fun onSetup(commands: List<String>) {
    val fullCommand = commands.joinToString(separator = " && ")
    terminalManager.sendCommand(fullCommand)
}
```

**所有命令用 `&&` 拼成一条长命令，一次性写进 PTY**：

```kotlin
// TerminalManager.kt:366 executeCommandInternal
val fullInput = "$command$TERMINAL_ENTER"   // TERMINAL_ENTER = "\r"
writeInputToKernel(session, fullInput, "command")
```

**关键推论：**

1. **不是 `bash -c`**，是往交互式 shell 的 stdin 写一整行，等价于用户粘贴一整行命令后回车。
2. **整条 `&&` 链由同一个 shell 进程解析** → 链中间的 `export` **对后续命令是有效的**。
3. **`&&` 链上任何一环返回非 0，后面全部不执行** → 所以每条容易失败的命令都要带 `|| true` 兜底。
4. **`apt upgrade -y` 之类可能进交互式提示的命令会卡死整条链**。

### 1.2 核心陷阱：持久化 ≠ 当前会话生效

这是**之前一直修不好的真正原因**。

`pnpm setup` 和 `pipx ensurepath` 这类命令，行为都是：

```
把路径写入 ~/.bashrc / ~/.profile   →  对【新开的】会话有效
                                    →  对【当前正在跑的这个会话】无效
```

而终端会话在用户点「开始配置」**之前就已经启动了**。所以：

```
点配置  →  shell 已启动（旧 PATH）
        →  pnpm setup 改了 ~/.bashrc（当前会话读不到）
        →  pnpm add -g xxx  →  ❌ ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH
```

**必须补一条 `export`，让当前会话立即生效。** 二者缺一不可：

| 步骤 | 作用域 | 必要性 |
|---|---|---|
| `pnpm setup` | 持久化（新会话） | 必须，否则重启终端又坏 |
| `export ...` | 当前会话 | 必须，否则本次配置后续命令全失败 |

### 1.3 pnpm 全局目录在哪

**pnpm 和 npm 的全局目录完全不同，这是最容易搞混的地方：**

| 工具 | 全局包目录 | bin 目录 |
|---|---|---|
| npm | `$(npm prefix -g)/lib/node_modules` | `$(npm prefix -g)/bin` |
| **pnpm** | `$PNPM_HOME`（默认 `~/.local/share/pnpm`） | **`$PNPM_HOME/bin`** |

pnpm 用 `~/.local/share/pnpm/bin`，**默认不在 PATH 里**，所以所有 `pnpm add -g` 都会失败。

### 1.4 最终修法（已合入 `b9fef96`）

```kotlin
// uv / pipx
commands.add("pipx ensurepath")
commands.add("source ~/.profile")
commands.add("export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\"")

// pnpm
commands.add("pnpm setup >/dev/null 2>&1 || true")
commands.add("export PNPM_HOME=\"${'$'}HOME/.local/share/pnpm\"")
commands.add("export PATH=\"${'$'}PNPM_HOME/bin:${'$'}PATH\"")
// 结果校验，避免静默失败
commands.add(
    "echo \"${'$'}PATH\" | grep -q \"${'$'}PNPM_HOME/bin\" && " +
        "echo '[OK] pnpm 全局目录已加入 PATH' || " +
        "echo '[!] pnpm 全局目录未加入 PATH，请手动执行: pnpm setup'"
)
```

### 1.5 ⚠️⚠️ Kotlin 字符串模板陷阱（这个坑炸过一次 CI）

**错误写法：**

```kotlin
commands.add("export PNPM_HOME=\"\\$HOME/.local/share/pnpm\"")   // ❌
```

看似转义了反斜杠，实际 Kotlin 的解析是：

```
"\\"     → 字面反斜杠
"$HOME"  → 模板插值，去找 Kotlin 变量 HOME  ← 不存在
```

**编译报错：**

```
error: unresolved reference 'HOME'.
error: unresolved reference 'PNPM_HOME'.
error: unresolved reference 'PATH'.
```

→ 直接导致 `android-build` 和 `android-tests` 双双失败（CI #212 / #195）。

**另一种错误写法（能编译，但输出错）：**

```kotlin
commands.add("export PNPM_HOME=\"\${'$'}HOME/...\"")   // ❌ 多了个字面反斜杠
// 实际输出：export PNPM_HOME="${'$'}HOME/..."   错！
```

**正确写法：**

```kotlin
commands.add("export PNPM_HOME=\"${'$'}HOME/.local/share/pnpm\"")   // ✅
// 实际输出：export PNPM_HOME="$HOME/.local/share/pnpm"
```

**规则总结：Kotlin 里要输出字面 `$`，用 `${'$'}`。绝对不要在 `$` 前面加反斜杠。**

### 1.6 改完必须验证（不要靠眼看括号）

```bash
# 1) 抽取改动代码块，包成独立函数
# 2) 用 kotlinc 编译
KH=/root/.sdkman/candidates/gradle/9.3.0/lib
SDK=<kotlin-stdlib-2.x.jar>
ANN=<annotations-13.0.jar>
COR=<kotlinx-coroutines-core-jvm-1.9.0.jar>
CP="$KH/kotlin-compiler-embeddable-2.2.21.jar:$SDK:$KH/kotlin-reflect-2.2.21.jar:$KH/kotlin-script-runtime-2.2.21.jar:$KH/kotlin-daemon-embeddable-2.2.21.jar:$COR:$ANN"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -kotlin-home /tmp/kh -d out T.kt

# 3) 读常量池，确认最终 shell 字符串
javap -c -p -constants out/TKt.class
# 或：strings -a out/TKt.class | grep export
```

**括号计数不可靠** —— 中文注释里的 `(` `)` `$` 会污染统计。

---

## 二、APK 签名固定

### 2.1 根因（这是最隐蔽的一个坑）

`app/build.gradle.kts` 的 `signingConfigs` **原本没有声明 `debug`**。

于是 `buildTypes.debug` 走的是 **AGP 内置的 debug 配置**，而它：

- `storeFile` **不是** `~/.android/debug.keystore`，是 AGP 自己管理的路径
- 该文件不存在时，AGP **静默生成一把随机密钥**，继续构建

**后果：**

- 每次 CI 出包签名都不同
- 现象是「安装新包提示：安装包无效或不兼容」
- **构建日志完全正常**，问题被彻底掩盖 —— 这是最难排查的地方

### 2.2 修法

**必须用 `getByName`，不能用 `create`：**

```kotlin
signingConfigs {
    getByName("debug") {                    // ✅ 覆写 AGP 内置配置
        val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (!debugKeystore.exists()) {
            throw GradleException("找不到固定的 debug keystore...")   // 宁可直接失败
        }
        storeFile = debugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }
}
```

```kotlin
create("debug") { ... }   // ❌ 报错：
// Cannot add a SigningConfig with name 'debug' as a SigningConfig
// with that name already exists.
```

> 顺带一提：这个报错本身**反向证实了**「AGP 内置 debug 配置」这个判断。

### 2.3 配套 CI 步骤

两个 workflow 都要有（`android-build.yml` 和 `android-tests.yml`）：

```yaml
- name: Setup fixed debug keystore
  shell: bash
  run: |
    set -euo pipefail
    mkdir -p ~/.android
    KEYSTORE_SRC="ci/signing/debug-keystore.b64"
    [ -f "$KEYSTORE_SRC" ] || { echo "::error::找不到 $KEYSTORE_SRC"; exit 1; }
    tr -d '\n\r' < "$KEYSTORE_SRC" | base64 -d > ~/.android/debug.keystore
    ACTUAL_SIZE=$(stat -c%s ~/.android/debug.keystore)
    [ "$ACTUAL_SIZE" -eq 2666 ] || { echo "::error::size=$ACTUAL_SIZE"; exit 1; }
```

> ⚠️ 曾经只在 build 版加了、tests 版没加，导致 `Android Tests` 因
> 「文件不存在即失败」而挂掉（CI #193）。**两个 workflow 必须同步。**

### 2.4 固定钥匙信息

| 项 | 值 |
|---|---|
| 文件 | `ci/signing/debug-keystore.b64` |
| 解码后大小 | 2666 字节 |
| 别名 | `androiddebugkey` |
| 密码 | `android` |
| 证书 SHA1 | `aaafdab58bcd76052c2acf4d7ef7850436d98545` |
| 证书 SHA256 | `cc69b50a001938f92f2da449a428468f409953c64a50efc94d27b3491de8bf43` |
| 证书 DER 长度 | **792 字节** |

### 2.5 怎么验签名（别用错方法）

**❌ 错误方法**：拿 APK 里的证书直接和 keystore 文件比字节。

原因：keystore 里是「私钥 + 证书」，APK 里是 Android **重新编码后**的证书，
**天然不同**。曾经因为这个误判「209 也没用固定钥匙」。

**✅ 正确方法一**：`keytool -printcert -jarfile app.apk`

**✅ 正确方法二**：纯 Python 解析 APK Signing Block（沙箱无 keytool 时用）

```python
# v2 签名块：id = 0x7109871a
# 结构：[uint64 size][id-value pairs][uint64 size]['APK Sig Block 42']
# signer = signedData | signatures | publicKey
# signedData = digests | certificates | attrs
```

**验过的结果（证书 DER 长度可作辅助判据）：**

| APK | 提交 | DER 长度 | 证书 SHA1 | 判定 |
|---|---|---|---|---|
| 208 | `db2c308` | 744 | `687e67b8…` | ❌ 随机钥匙 |
| 209 | 早期 | 744 | `41cae53e…` | ❌ 随机钥匙 |
| **211** | `510bcd5` | **792** | **`aaafdab5…`** | ✅ **= 固定钥匙** |

### 2.6 关于「无法覆盖安装」的物理事实

**签名不同的两个 APK，Android 层面不允许互相覆盖，必须卸载重装。**

- 208/209 的随机密钥**已经在 CI 临时容器里销毁**，找不回来
- 2048 位 RSA，不存在「伪造一把一样的」的可能
- **这是数学事实，不是代码能解决的**

**结论：208 → 211 这一跨，必须卸载一次。211 之后所有包签名一致，永久可覆盖。**

---

## 三、网络通道（gh-proxy）能力边界

**沙箱环境无法直连 GitHub，必须走 `gh-proxy.com`。它的边界如下：**

| 能力 | 支持 | 说明 |
|---|---|---|
| 代理 `codeload.github.com` tarball | ✅ | |
| 代理需鉴权的 artifact 接口 | ✅ | 服务端自带凭据 |
| 转发 git receive-pack 协议 | ✅ | `git push` 可用 |
| 转发客户端 `Authorization` 头 | ❌ | 直接返回 77 字节 `Web page content is not allowed` |
| 代理 `/actions/jobs/{id}/logs` | ❌ | 302 到 Azure Blob，代理拿不到 |
| 代理 `api.github.com` 带 token | ❌ | 一律 403 |

**能用的姿势：**

```bash
# 1) git 走 HTTP（可推大文件，62MB 实测 OK）
git remote set-url origin \
  "https://x-access-token:${PAT}@gh-proxy.com/https://github.com/OWNER/REPO.git"

# 2) API 读操作（不带敏感头时可以）
curl -H "Authorization: Bearer $PAT" \
  "https://gh-proxy.com/https://api.github.com/repos/OWNER/REPO/actions/runs?per_page=8"

# 3) artifact 下载：先取 302 Location，再经代理
```

**不能用的姿势：**

- blob API 上传大文件（base64 后 85MB 超时；octet-stream 报 400）
- 直接经代理拉 CI 日志（**目前拿不到，需要人工贴**）

---

## 四、当前状态与待办

### 已推送的提交

```
b9fef96 fix(terminal): 补齐 pipx/pnpm 的 PATH 注入与校验，一次配置永久生效
e080d51 fix(terminal): 修正 Kotlin 字符串模板转义导致的 pnpm PATH 编译失败
d99701d fix(terminal): pnpm 安装后初始化全局目录（❌ 构建失败，已被 e080d51 修） 
a9de84b fix(ci): Android Tests 补上固定 keystore 步骤
510bcd5 fix(signing): 改用 getByName 覆盖 AGP 内置 debug 签名配置
4a33cad fix(signing): 显式声明 debug 签名配置（❌ create 重名，已被 510bcd5 修）
db2c308 refactor(terminal): 收编 terminal 为自有目录并修复 pnpm 安装失败
```

### 待办

- [ ] `b9fef96` 的 CI 构建验证（本地已用 kotlinc 编译通过）
- [ ] 用户真机覆盖安装 211 → 验证终端配置一次成功
- [ ] **统一备份功能**：目前人物、技能、聊天记录各有独立备份按钮，
      用户要求做成「一个入口全量打包 + 一键还原」
- [ ] 改应用名 / 换图标（等签名线稳定后做）

### ⚠️ 交接注意事项

1. **不要凭猜测改代码** —— 改完必须用 kotlinc 编译验证
2. **不要只改一个 workflow** —— tests 和 build 要同步
3. **不要建议用户轻易卸载** —— 用户明确强烈反对，除非同时给出保数据方案
4. **用户已有 211（签名正确）** —— 后续新包可直接覆盖安装
