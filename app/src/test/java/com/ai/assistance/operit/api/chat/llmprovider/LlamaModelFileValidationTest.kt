package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.api.chat.llmprovider.LlamaProvider.Companion.classifyModelFileProblem
import com.ai.assistance.operit.api.chat.llmprovider.LlamaProvider.Companion.resolveModelFile
import com.ai.assistance.operit.api.chat.llmprovider.LlamaProvider.ModelFileProblem
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地模型文件校验（闪退回归防护）。
 *
 * 复现的用户场景：**模型名称栏留空** → 聊天框发消息 → 一直转 → 闪退。
 * 根因是历史实现只判断 `exists()`，而 Android 上 `File("")` 会解析成当前工作目录
 * （`models/llama` 文件夹），目录 `exists() == true` 于是被当成合法模型路径喂给 native，
 * GGUF 解析在 native 层失败导致崩溃。
 *
 * 这些用例全部是纯 JVM 逻辑（不依赖 Android Context），覆盖每一个失败分支。
 */
class LlamaModelFileValidationTest {

    private fun tempDir(): File = Files.createTempDirectory("llama-model-test").toFile()

    // ---------------------------------------------------------------------
    // 核心回归：目录绝不能被当成模型文件
    // ---------------------------------------------------------------------

    @Test
    fun `directory path is rejected as IS_DIRECTORY not accepted`() {
        val dir = tempDir()
        // 关键断言：即使目录 exists() == true，也必须被判定为 IS_DIRECTORY
        assertEquals(
            ModelFileProblem.IS_DIRECTORY,
            classifyModelFileProblem(dir, dir.absolutePath),
        )
        assertNull("目录绝不能通过校验", resolveModelFile(dir.absolutePath, null))
    }

    @Test
    fun `empty model name is rejected as NOT_SELECTED`() {
        assertEquals(ModelFileProblem.NOT_SELECTED, classifyModelFileProblem(File(""), ""))
        assertNull(resolveModelFile("", tempDir()))
        assertNull(resolveModelFile("   ", tempDir()))
    }

    // ---------------------------------------------------------------------
    // 各失败分支
    // ---------------------------------------------------------------------

    @Test
    fun `missing path is reported as NOT_FOUND`() {
        val missing = File(tempDir(), "nope.gguf")
        assertEquals(
            ModelFileProblem.NOT_FOUND,
            classifyModelFileProblem(missing, missing.absolutePath),
        )
    }

    @Test
    fun `non gguf file is reported as NOT_GGUF`() {
        val dir = tempDir()
        val bin = File(dir, "weights.bin").apply { writeText("not a gguf") }
        assertEquals(ModelFileProblem.NOT_GGUF, classifyModelFileProblem(bin, bin.absolutePath))
    }

    /** 扩展名判定必须与大小写无关。 */
    @Test
    fun `uppercase GGUF extension is accepted`() {
        val dir = tempDir()
        val model = File(dir, "Qwen2.5-3B-Q4_K_M.GGUF").apply { writeText("gguf") }
        assertNull(classifyModelFileProblem(model, model.absolutePath))
        assertNotNull(resolveModelFile(model.absolutePath, null))
    }

    @Test
    fun `directory whose name ends with gguf is still rejected`() {
        val dir = tempDir()
        // 极端用例：伪装成 .gguf 的目录，仍然必须被 isFile 拦下
        val tricky = File(dir, "decoy.gguf").apply { mkdirs() }
        assertEquals(
            ModelFileProblem.IS_DIRECTORY,
            classifyModelFileProblem(tricky, tricky.absolutePath),
        )
    }

    // ---------------------------------------------------------------------
    // 通过路径（绝对路径 = 文件选择器场景）
    // ---------------------------------------------------------------------

    @Test
    fun `absolute path to real gguf passes validation`() {
        val dir = tempDir()
        val model = File(dir, "model.gguf").apply { writeText("gguf-bytes") }
        assertNull(classifyModelFileProblem(model, model.absolutePath))
        assertEquals(model.absoluteFile, resolveModelFile(model.absolutePath, null)?.absoluteFile)
    }

    /** 相对文件名走 modelsDir 基准目录（历史行为，不能破坏）。 */
    @Test
    fun `relative file name resolves against models dir`() {
        val modelsDir = tempDir()
        File(modelsDir, "qwen.gguf").writeText("gguf")
        val resolved = resolveModelFile("qwen.gguf", modelsDir)
        assertNotNull(resolved)
        assertEquals(File(modelsDir, "qwen.gguf").absoluteFile, resolved!!.absoluteFile)
    }

    /** 相对文件名在基准目录里不存在时必须失败，不能退化成"就用目录"。 */
    @Test
    fun `relative file name missing in models dir fails`() {
        assertNull(resolveModelFile("ghost.gguf", tempDir()))
    }

    /** 没有基准目录时只接受绝对路径。 */
    @Test
    fun `relative name without models dir is rejected`() {
        assertNull(resolveModelFile("qwen.gguf", null))
    }

    /** 用户手填路径常带首尾空格，必须 trim 后正常通过。 */
    @Test
    fun `surrounding whitespace is trimmed`() {
        val dir = tempDir()
        val model = File(dir, "model.gguf").apply { writeText("gguf") }
        assertNotNull(resolveModelFile("  ${model.absolutePath}  ", null))
    }
}
