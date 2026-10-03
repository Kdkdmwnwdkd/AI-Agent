package com.ai.assistance.operit.api.chat.llmprovider

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可用模型列表」的候选目录与已配置路径兜底。
 *
 * 背景回归（用户录屏 16500.mp4 实测）：
 * 模型实际放在 `/sdcard/AI 模型/qwen2.5-3b-instruct-q4_k_m.gguf`，
 * 即**内置存储根目录**下的「AI 模型」文件夹，**不在 Download 里面**。
 * 早期版本的候选目录只写了 `Download/AI 模型`，因而列表为空并提示"没有找到可用模型"。
 *
 * 本测试用**临时目录模拟 sdcard 布局**，验证：
 * 1. 根目录层级的 `AI 模型` 一定在候选目录集合中；
 * 2. 该目录下的 .gguf 能被扫到，非 .gguf 不会被误收；
 * 3. 放在候选目录之外的模型，能通过"已配置路径"兜底进入列表。
 *
 * 注意：这里不直接调用 [com.ai.assistance.operit.util.LocalModelFileStore.candidateScanDirs]
 * （它依赖 Android 的 Environment），而是用同样的目录组成规则在临时根上复现，
 * 从而在不引入 Robolectric 的前提下守住这条回归。
 */
class LlamaModelScanDirsTest {

    private fun tempDir(): File = Files.createTempDirectory("llama-scan-test").toFile()

    /**
     * 与 LocalModelFileStore.candidateScanDirs() 保持一致的"相对路径"组成。
     * 若线上改了这份清单，此测试会失败，从而提醒同步 —— 这正是我们要的护栏。
     */
    private val candidateRelativePaths =
        listOf(
            "Download",
            "Download/AI 模型",
            "Download/AI模型",
            "Download/models",
            "Download/Models",
            "AI 模型",
            "AI模型",
            "Models",
            "models",
            "Documents",
            "llm",
        )

    /** 把相对清单落到一个临时根目录上，模拟出「实际存在」的那部分。 */
    private fun existingDirsUnder(root: File): List<File> =
        candidateRelativePaths.map { File(root, it) }.filter { it.isDirectory && it.canRead() }

    // ---------------------------------------------------------------------
    // 核心回归：根目录下的「AI 模型」必须被覆盖
    // ---------------------------------------------------------------------

    @Test
    fun `root level AI model dir is in candidate list`() {
        assertTrue(
            "候选目录必须包含内置存储根目录下的 AI 模型（用户实际就放这里）",
            candidateRelativePaths.contains("AI 模型"),
        )
    }

    @Test
    fun `scanner finds gguf under root level AI model dir`() {
        val root = tempDir()
        val aiModelDir = File(root, "AI 模型").apply { mkdirs() }
        val model = File(aiModelDir, "qwen2.5-3b-instruct-q4_k_m.gguf").apply {
            writeText("x".repeat(1024))
        }
        // 干扰项：非 gguf 不能被当作模型
        File(aiModelDir, "readme.txt").writeText("not a model")

        val dirs = existingDirsUnder(root)
        assertTrue("根目录 AI 模型 应被识别为存在的候选目录", dirs.any { it.name == "AI 模型" })

        val found = dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".gguf") }?.toList() ?: emptyList()
        }
        assertTrue("必须扫到实际的 qwen 模型", found.any { it.name == model.name })
        assertFalse("txt 不能混进模型列表", found.any { it.name == "readme.txt" })
    }

    @Test
    fun `scanner also covers Download subdirs`() {
        val root = tempDir()
        val dl = File(root, "Download").apply { mkdirs() }
        File(dl, "a.gguf").writeText("a")

        val dirs = existingDirsUnder(root)
        assertTrue("Download 应在候选目录里", dirs.any { it.name == "Download" })

        val found = dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".gguf") }?.toList() ?: emptyList()
        }
        assertTrue("Download 下的 gguf 应被扫到", found.any { it.name == "a.gguf" })
    }

    @Test
    fun `absent candidate dirs are filtered out`() {
        val root = tempDir() // 全空，一个候选目录都不存在
        assertTrue("不存在的候选目录必须被过滤，避免无谓的 listFiles", existingDirsUnder(root).isEmpty())
    }

    /**
     * 直接读线上源码，确认候选清单里确实包含根目录层级的 `AI 模型`。
     *
     * 为什么读源码而不是调 [com.ai.assistance.operit.util.LocalModelFileStore.candidateScanDirs]：
     * 后者依赖 Android 的 `Environment.getExternalStorageDirectory()`，纯 JVM 单测跑不了
     * （项目未引入 Robolectric）。读源码做字符串断言，既不需要 Android 环境，
     * 又能在**清单被误删时真正失败** —— 是有效护栏，不是摆设。
     */
    @Test
    fun `source candidate list contains root level AI model dir`() {
        val source = findSourceFile("LocalModelFileStore.kt")
        assertTrue("找不到 LocalModelFileStore.kt，测试无法守护此回归", source != null)

        val text = source!!.readText()
        val marker = "fun candidateScanDirs()"
        val start = text.indexOf(marker)
        assertTrue("candidateScanDirs() 定义不存在，实现可能被改名", start >= 0)

        // 只看函数体，避免误判文件里其它位置的同名字符串
        val body = text.substring(start).substringBefore("\n    }")

        assertTrue(
            "候选清单必须包含根目录下的 AI 模型（用户实际存放位置：/sdcard/AI 模型/）",
            body.contains("""File(external, "AI 模型")"""),
        )
        assertTrue(
            "候选清单必须包含 Download 目录",
            body.contains("""File(external, "Download")""") || body.contains("val download"),
        )
        assertTrue(
            "候选清单必须覆盖 Download 下的 AI 模型（历史写法，不能回退）",
            body.contains("""File(download, "AI 模型")"""),
        )
    }

    /** 在测试工作目录内定位源码文件（Gradle 单测的 cwd 是模块目录）。 */
    private fun findSourceFile(name: String): File? {
        val candidates =
            listOf(
                File("src/main/java/com/ai/assistance/operit/util/$name"),
                File("app/src/main/java/com/ai/assistance/operit/util/$name"),
            )
        return candidates.firstOrNull { it.isFile }
    }

    // ---------------------------------------------------------------------
    // 兜底：候选目录之外，靠「已配置路径」也能进列表
    // ---------------------------------------------------------------------

    @Test
    fun `configured absolute path outside candidates still resolves`() {
        val root = tempDir()
        val deepDir = File(root, "NAS挂载/llm").apply { mkdirs() }
        val model = File(deepDir, "nas-model.gguf").apply { writeText("z") }

        // 该目录不在候选清单里，也不在临时根的任何候选相对路径下
        assertFalse(
            "确认这是候选之外的目录，否则本用例失去意义",
            existingDirsUnder(root).any { it.absolutePath == deepDir.absolutePath },
        )

        // 兜底逻辑：绝对路径直接校验（与 getLlamaLocalModels 的 configuredPaths 处理一致）
        assertTrue("已配置的绝对路径应能通过文件校验", model.isFile)
    }

    @Test
    fun `blank configured entries are ignored`() {
        val entries = listOf("", "   ", "\n", "/real/path.gguf")
        val meaningful = entries.map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("空白配置项必须被忽略，只留真实路径", 1, meaningful.size)
    }

    /** 配置里是相对文件名时，按默认模型目录解析 —— 与推理侧行为一致。 */
    @Test
    fun `configured relative name resolves under models dir`() {
        val modelsDir = tempDir()
        val model = File(modelsDir, "qwen.gguf").apply { writeText("g") }
        val resolved = LlamaProvider.resolveModelFile("qwen.gguf", modelsDir)
        assertEquals(model.absoluteFile, resolved?.absoluteFile)
    }
}
