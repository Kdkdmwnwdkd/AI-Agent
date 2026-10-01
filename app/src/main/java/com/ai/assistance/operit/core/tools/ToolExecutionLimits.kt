package com.ai.assistance.operit.core.tools

object ToolExecutionLimits {
    const val MAX_FILE_READ_BYTES = 32_000
    const val DEFAULT_FILE_READ_PART_LINES = 200
    const val MAX_TEXT_RESULT_LENGTH = 5_000
    /** Maximum serialized size of one tool result sent back to the model. */
    const val MAX_SINGLE_TOOL_RESULT_MESSAGE_CHARS = MAX_FILE_READ_BYTES * 2

    /**
     * read_file_binary 的硬上限。
     *
     * 为什么需要它：该工具把整个文件读进内存后再做 Base64 编码，编码结果额外膨胀约 33%，
     * 峰值内存约为文件大小的 1.34 倍。不设上限时，读取大文件必然触发 OutOfMemoryError，
     * 结果是整个进程被系统杀死（丢失未保存数据），而不是返回一个可处理的失败结果。
     *
     * 200MB 这个值只用于挡住"离谱"的输入；真正能否读取由 rejectBinaryReadReason()
     * 按当前可用堆动态判定，避免在小内存设备上仍然 OOM。
     */
    const val MAX_BINARY_READ_BYTES = 200L * 1024 * 1024

    /** Base64 相对原始字节的膨胀系数，用于估算峰值内存。1.34 覆盖 4/3 编码 + 少量临时缓冲。 */
    private const val BASE64_MEMORY_FACTOR = 1.34

    /** 读取二进制文件时允许占用的可用堆比例，留出一半余量给编码结果和调用链上的其他对象。 */
    private const val BINARY_READ_HEAP_FRACTION = 0.5

    /**
     * 判断给定大小的二进制文件在当前可用堆下是否读得下。
     *
     * 返回 null 表示可以读取；返回非 null 表示不能读取，字符串是给调用方直接返回的具体原因。
     * 用"返回原因"而不是布尔值，是为了让错误信息带上实际数字，便于定位是文件太大还是设备内存太小。
     *
     * 重要：这个字符串会作为 ToolResult.error 直接交给模型。模型看不到设备的堆状态，
     * 如果只报"文件多大、内存多少"，它无法判断该重试、该换工具、还是该放弃，往往只能
     * 原样重试同一个调用再失败一次。因此每条拒绝都必须带上"现在能安全读多大"和
     * "该改用哪个工具"，让模型一次就能改对。
     */
    fun rejectBinaryReadReason(fileSizeBytes: Long): String? {
        val runtime = Runtime.getRuntime()
        val availableHeapBytes =
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        val usableHeapBytes = (availableHeapBytes * BINARY_READ_HEAP_FRACTION).toLong()
        // 反推：在当前可用堆下，多大的文件还能读得下。向下取整，
        // 保证报出的数字是"确实能过检查"的保守值，不会出现"说能读 96MB 但 96MB 被拒"。
        val maxReadableMb = (usableHeapBytes / BASE64_MEMORY_FACTOR / 1024 / 1024).toLong()
        val fileMb = ceilDiv(fileSizeBytes, 1024 * 1024)
        val advice =
            "Read it in parts with read_file_part (start_line/end_line) instead, " +
                "or use grep_code to extract only the matching lines, " +
                "or process it in the terminal without loading it into memory " +
                "(e.g. 'head', 'tail', 'grep', 'awk')."

        if (fileSizeBytes > MAX_BINARY_READ_BYTES) {
            return "Cannot read this file in full: it is ${fileMb}MB, which exceeds the " +
                "${MAX_BINARY_READ_BYTES / 1024 / 1024}MB hard limit for reading a whole file into memory. $advice"
        }

        val requiredBytes = (fileSizeBytes * BASE64_MEMORY_FACTOR).toLong()
        if (requiredBytes > usableHeapBytes) {
            // 需求侧向上取整、可用侧向下取整：否则出现 "needs 128MB but only 128MB usable"
            // 这种看着相等却被拒的消息（实际是 122.9MB > 128MB 之外的小数差），
            // 读的人会以为判定有 bug。取整方向保证展示出来的两个数一定满足 ">"。
            val requiredMb = ceilDiv(requiredBytes, 1024 * 1024)
            val usableMb = usableHeapBytes / 1024 / 1024
            return "Cannot read this file in full: it is ${fileMb}MB, and reading it needs about " +
                "${requiredMb}MB of memory, but only ${usableMb}MB " +
                "can be safely used on this device right now (device heap limit " +
                "${runtime.maxMemory() / 1024 / 1024}MB, currently available " +
                "${availableHeapBytes / 1024 / 1024}MB). Files up to about ${maxReadableMb}MB can still be " +
                "read in full right now. $advice"
        }
        return null
    }

    /** 向上取整的整数除法，用于把字节数换算成 MB 时避免向下取整造成"看起来相等却被拒"。 */
    private fun ceilDiv(value: Long, unit: Long): Long = (value + unit - 1) / unit
}
