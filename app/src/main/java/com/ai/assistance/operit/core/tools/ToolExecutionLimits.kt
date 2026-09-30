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
     */
    fun rejectBinaryReadReason(fileSizeBytes: Long): String? {
        if (fileSizeBytes > MAX_BINARY_READ_BYTES) {
            return "File is too large to read as binary: $fileSizeBytes bytes exceeds the " +
                "${MAX_BINARY_READ_BYTES} byte limit (${MAX_BINARY_READ_BYTES / 1024 / 1024}MB). " +
                "Use read_file_part for text, or process the file without loading it into memory."
        }

        val runtime = Runtime.getRuntime()
        val availableHeapBytes =
            runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        val requiredBytes = (fileSizeBytes * BASE64_MEMORY_FACTOR).toLong()
        val usableHeapBytes = (availableHeapBytes * BINARY_READ_HEAP_FRACTION).toLong()

        if (requiredBytes > usableHeapBytes) {
            return "Not enough memory to read this file: needs about ${requiredBytes / 1024 / 1024}MB " +
                "(file ${fileSizeBytes / 1024 / 1024}MB after Base64 expansion), but only " +
                "${usableHeapBytes / 1024 / 1024}MB of heap is usable " +
                "(max ${runtime.maxMemory() / 1024 / 1024}MB, available ${availableHeapBytes / 1024 / 1024}MB)."
        }
        return null
    }
}
