package com.ai.assistance.operit.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地模型文件的选择与解析。
 *
 * 背景：本地模型（llama.cpp 的 `.gguf`）原本只能通过「把文件手动放进
 * `Download/玄枵/models/llama`」来使用，对用户极不友好。
 *
 * 现在用户可以通过系统文件选择器从**任意位置**挑一个模型文件，App 会**记住它的真实路径**
 * 并长期使用 —— 不复制、不搬移、不产生额外空间占用，重启 App 后依然有效。
 *
 * 例如：第一次选了 `Documents/A.gguf` 就一直用 A；下次改选 `Documents/B.gguf` 就一直用 B。
 *
 * 为什么能这么做：native 推理层（`LlamaSession`）只接受真实文件路径，
 * 而系统文件选择器返回的 document 大多可以还原成 `/storage/...` 下的真实路径。
 * 仅当所选位置**无法还原真实路径**时（极少数受限目录），才明确报错提示用户换一个位置，
 * 而不是悄悄复制文件 —— 保证行为可预期。
 */
object LocalModelFileStore {

    private const val TAG = "LocalModelFileStore"

    /** llama.cpp 模型目录：`Download/玄枵/models/llama`（用户不放这里也能用，仅作默认推荐位置） */
    fun llamaModelsDir(): File = File(OperitPaths.operitRootDir(), "models/llama")

    /** MNN 模型目录：`Download/玄枵/models/mnn` */
    fun mnnModelsDir(): File = File(OperitPaths.operitRootDir(), "models/mnn")

    /**
     * 把用户选中的文件解析成 **native 层可直接加载的绝对路径**。
     *
     * 该路径会被写入模型配置并长期复用，因此必须是稳定的真实路径。
     *
     * @param selection 用户选中的内容 —— 可以是 `content://` URI，也可以是普通绝对路径。
     * @throws IllegalStateException 无法还原真实路径、文件不存在或当前无读取权限时抛出。
     */
    suspend fun resolveToFilePath(context: Context, selection: String): String =
        withContext(Dispatchers.IO) {
            val trimmed = selection.trim()
            if (trimmed.isEmpty()) {
                throw IllegalStateException("未选择任何文件")
            }

            // 已经是普通路径：直接校验后使用
            if (trimmed.startsWith("/")) {
                val file = File(trimmed)
                if (!file.exists()) throw IllegalStateException("文件不存在：$trimmed")
                if (!file.isFile) throw IllegalStateException("所选路径不是文件：$trimmed")
                return@withContext file.absolutePath
            }

            // SAF URI：还原真实路径
            val uri = Uri.parse(trimmed)
            val direct = directPathOf(context, uri)
                    ?: throw IllegalStateException(
                            "该位置不支持直接读取，请在文件管理器中换个位置选择（推荐 Download 或 Documents 目录）"
                    )

            // 校验可读性：能打开输入流才说明权限有效
            val readable = runCatching {
                context.contentResolver.openInputStream(uri)?.use { /* 只验证可打开 */ true } ?: false
            }.getOrDefault(false)

            if (!readable) {
                throw IllegalStateException("没有读取该文件的权限，请重新选择一次")
            }

            AppLogger.d(TAG, "模型文件已锁定：$direct")
            direct
        }

    /**
     * 尝试把 SAF URI 还原为真实文件系统路径。
     *
     * 系统文件选择器通常返回 `content://com.android.externalstorage.documents/...`，
     * 其 document id 形如 `primary:Documents/model.gguf`，
     * 可还原为 `/storage/emulated/0/Documents/model.gguf`。
     *
     * @return 真实路径；无法可靠还原时返回 null。
     */
    fun directPathOf(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { path -> File(path).takeIf { it.isFile }?.absolutePath }
        }
        if (uri.scheme != "content") return null

        return runCatching {
            val authority = uri.authority ?: return@runCatching null

            // 仅处理 externalstorage 提供的文档（手机存储 / SD 卡 / OTG U 盘）
            if (!authority.contains("externalstorage")) return@runCatching null

            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size != 2) return@runCatching null

            val volume = parts[0]
            val relative = parts[1]
            val root = volumeRoot(volume) ?: return@runCatching null

            File(root, relative).takeIf { it.isFile }?.absolutePath
        }.getOrNull()
    }

    /** 根据 externalstorage 的 volume 名解析挂载根目录（primary / 形如 `XXXX-XXXX` 的可移动存储）。 */
    private fun volumeRoot(volume: String): File? {
        if (volume.equals("primary", ignoreCase = true)) {
            return android.os.Environment.getExternalStorageDirectory()
        }
        // 可移动存储（SD 卡 / U 盘）：在 /storage 下按卷名匹配
        val storageDir = File("/storage")
        if (!storageDir.isDirectory) return null
        return storageDir.listFiles()?.firstOrNull { it.name.equals(volume, ignoreCase = true) }
    }

    /** 查询 SAF 文档的显示名（`OpenableColumns.DISPLAY_NAME`），用于界面展示。 */
    fun displayNameOf(context: Context, uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment?.substringAfterLast('/')
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    /**
     * 判断一个已保存的模型路径当前是否可用（文件还在、还能读）。
     * 用于设置页给出「文件已失效」的提示，而不是等到聊天时才报错。
     */
    fun isUsable(path: String, defaultDir: File? = null): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return false
        val file = if (File(trimmed).isAbsolute) File(trimmed) else defaultDir?.let { File(it, trimmed) }
        return file?.isFile == true
    }

    /**
     * 「可用模型列表」的候选扫描目录。
     *
     * 设计原则：**只扫一层，不全盘递归**。手机存储动辄上万文件，递归扫描既慢又可能触发
     * 权限异常，得不偿失。这里仅覆盖模型最可能出现的几个位置。
     *
     * 覆盖顺序（越靠前越优先）：
     * 1. `Download/玄枵/models/llama` —— 官方推荐位置（由调用方自行加入，不在此列表）
     * 2. `/sdcard/Download` 与 `/sdcard/Download/AI 模型`、`/sdcard/Download/models`
     *    —— 用户从浏览器 / 网盘下载 gguf 的默认落点
     * 3. 内置存储根目录下少量常见目录（`Documents` / `Models` / `models`）
     *
     * 统一过滤掉不存在或不可读的目录，调用方无需再判空。
     */
    fun candidateScanDirs(): List<File> {
        val external = android.os.Environment.getExternalStorageDirectory() ?: return emptyList()
        val download = File(external, "Download")
        val candidates =
            listOf(
                download,
                File(download, "AI 模型"),
                File(download, "models"),
                File(download, "Models"),
                File(external, "Documents"),
                File(external, "Models"),
                File(external, "models"),
            )
        return candidates.filter { it.isDirectory && it.canRead() }
    }
}
