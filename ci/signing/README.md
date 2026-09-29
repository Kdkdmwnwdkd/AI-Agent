# CI 签名密钥目录

## 文件

| 文件 | 说明 |
|---|---|
| `debug-keystore.b64` | **纯 base64**，无任何说明文字。CI 与本地直接解码使用 |
| `README.md` | 本文件 |

> ⚠️ **`debug-keystore.b64` 必须保持"纯 base64"**：只允许
> `A-Z a-z 0-9 + / =` 与换行。任何说明文字、markdown 标记、
> 注释都会进入解码器并导致 `base64: invalid input`。
> 说明请一律写入本 README。
>
> 该约束来自一次真实事故：初版把说明文档和 base64 放在同一文件里，
> 导致 CI 连续两轮失败，且因为说明中恰好引用了
> "base64: invalid input" 这行错误文本（被反引号包裹而切分了文件），
> 让排查方向一度偏离。

## 用途

CI 每次出包（`assembleDebug`）需要**固定不变**的签名密钥。若缺失，
Gradle 会为每次构建自动生成随机 debug key，导致：

- 每个 APK 的签名都不同
- 安装新版时必须先卸载旧版（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）
- 应用数据（模型配置、密钥、角色卡）随之被清空

原先该密钥依赖 `DEBUG_KEYSTORE_B64` 仓库 Secret。但 Secret 未配置时，
`echo "" | base64 -d` 会静默产出 **0 字节文件且不报错**，
Gradle 随即回退到随机 key —— CI 依然显示成功，问题被完全掩盖。
改用仓库内文件后，缺失即为明确失败，不可能再静默降级。

## 为什么可以公开

这是 **debug** 密钥，不是发布密钥：

- 密码为 Android 生态公开约定值 `android`
- alias 为约定值 `androiddebugkey`
- 证书为自签名，`CN=Android Debug`

它不承载任何机密，唯一作用是"让签名保持稳定"。本仓库为私有仓库，
debug 包本就不用于对外分发。**正式发布包必须使用独立的 release 密钥。**

## 恢复方式

```bash
tr -d '\n\r' < ci/signing/debug-keystore.b64 | base64 -d > ~/.android/debug.keystore
chmod 600 ~/.android/debug.keystore
```

CI 中的等价写法见 `.github/workflows/android-build.yml` 的
`Setup fixed debug keystore` 步骤。

## 校验值

| 项 | 值 |
|---|---|
| base64 文件大小 | 3603 字节（47 行，76 列换行） |
| 解码后大小 | **2666** 字节 |
| keystore SHA256 | `04eaa4f5397bcc3e39509076159add6f9151ab4770bd1bba8b64995ca1c0b416` |
| 证书 SHA256 | `CC:69:B5:0A:00:19:38:F9:2F:2D:A4:49:A4:28:46:8F:40:99:53:C6:4A:50:EF:C9:4D:27:B3:49:1D:E8:BF:43` |
| 有效期 | 2026-09-29 → 2054-02-14 |
| 密码 / alias | `android` / `androiddebugkey` |

**若此文件丢失，所有已安装的 debug 包将无法覆盖升级**，请另行留存备份。
