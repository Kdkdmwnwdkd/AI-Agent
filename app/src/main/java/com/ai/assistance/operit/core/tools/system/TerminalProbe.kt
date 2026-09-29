package com.ai.assistance.operit.core.tools.system

import com.ai.assistance.operit.util.AppLogger

/**
 * 终端环境探测：判断某个命令在终端会话里是否【真的可以执行】。
 *
 * ## 为什么不能用 `输出.contains("xxx")`
 *
 * 历史实现是：
 *
 * ```
 * val result = terminal.executeCommand(sessionId, "command -v pnpm")
 * val installed = result != null && result.contains("pnpm")
 * ```
 *
 * 这是裸子串匹配，完全没有"命令到底成没成功"的校验，至少有两类误判：
 *
 * 1. 终端回显：命令本身（`command -v pnpm`）会被回显进输出流，关键字“pnpm”必然出现；
 * 2. 报错文本：`pnpm: not found` 之类的错误信息同样带着关键字。
 *
 * 一旦误判成“已安装”，上层会把「NodeJS / Python 环境已就绪」置为 true，
 * 而这个状态正是配置向导入口的显隐条件（见 `ShizukuDemoScreen`）——
 * 结果就是：环境其实没配好，配置入口却消失了，用户被锁在外面点不到任何东西。
 *
 * ## 这里的做法
 *
 * 1. 真正执行目标命令（默认 `--version`），而不是只用 `command -v` 找路径；
 * 2. 只认命令自己打印出来的固定标记；
 * 3. 标记在命令行里被 `%s` 拆开书写，回显文本拼不出完整标记，
 *    因此回显与报错文本都不会造成误判。
 */
object TerminalProbe {

    private const val TAG = "TerminalProbe"

    /** 对应命令行中的 `printf 'OP_PROBE_%s\n' OK`；回显里不会出现这个完整片段。 */
    private const val MARKER = "OP_PROBE_OK"

    /**
     * 探测 [command] 在 [sessionId] 会话里能否真正执行。
     *
     * @param probeArgs 用于确认可执行性的参数，默认 `--version`。
     * @return 只有命令成功执行并打印出标记时才为 true；
     *         会话异常、命令缺失、执行失败一律返回 false（宁可判成“没装”，也不能误判成“装了”）。
     */
    suspend fun isRunnable(
        terminal: Terminal,
        sessionId: String,
        command: String,
        probeArgs: String = "--version"
    ): Boolean {
        // 标记拆成 'OP_PROBE_%s' + 'OK' 两段书写：
        // 终端把这条命令回显出来时，输出里只会出现 OP_PROBE_%s 和 OK，
        // 拼不成完整的 OP_PROBE_OK，所以标记只可能来自命令的真正执行结果。
        val shell = "$command $probeArgs >/dev/null 2>&1 && printf 'OP_PROBE_%s\\n' OK || true"

        return try {
            val result = terminal.executeCommand(sessionId, shell)
            val ok = result != null && result.contains(MARKER)
            AppLogger.d(TAG, "probe [$command $probeArgs] -> $ok")
            ok
        } catch (e: Exception) {
            AppLogger.e(TAG, "probe [$command $probeArgs] failed: ${e.message}", e)
            false
        }
    }
}
