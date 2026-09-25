package com.ai.assistance.operit.data.model

/**
 * Phase 3: Memory Tier Architecture (Skeleton)
 *
 * 三层记忆分层，灵感来自认知心理学。
 * 纯分类层，不修改 Memory 实体（避免 ObjectBox 模型重建和命名冲突）。
 *
 * 分类方式：通过 Memory 现有字段推断层级归属
 *   - folderPath 前缀: "working/", "episodic/", "semantic/"
 *   - tags: 包含层级标签
 *   - 内容特征: 长度、来源、类型
 *
 * 注意：主分支已有 `MemorySpace` 数据类（偏好设置），因此本文件使用 `MemoryTier` 命名。
 */
enum class MemoryTier(
    val displayName: String,
    val description: String,
    val defaultRetentionDays: Int
) {
    WORKING("工作记忆", "当前会话和短期上下文", 1),
    EPISODIC("情景记忆", "对话历史和事件序列", 30),
    SEMANTIC("语义记忆", "知识库和长期事实", 365);

    companion object {
        fun fromString(value: String): MemoryTier {
            return values().find { it.name == value } ?: EPISODIC
        }
    }
}

/**
 * Memory 扩展：推断记忆所属层级。
 * 基于现有字段（folderPath/tags），零侵入。
 */
fun Memory.inferMemoryTier(): MemoryTier {
    val fp = folderPath?.lowercase() ?: ""
    return when {
        fp.startsWith("working/") || fp == "working" -> MemoryTier.WORKING
        fp.startsWith("semantic/") || fp == "semantic" -> MemoryTier.SEMANTIC
        tags.any { it.name.lowercase() in listOf("working", "short-term") } -> MemoryTier.WORKING
        tags.any { it.name.lowercase() in listOf("semantic", "knowledge", "long-term") } -> MemoryTier.SEMANTIC
        isDocumentNode || content.length > 5000 -> MemoryTier.SEMANTIC
        source == "chat_summary" || source == "conversation" -> MemoryTier.EPISODIC
        else -> MemoryTier.EPISODIC
    }
}

/**
 * 检查记忆是否需要升级到更长保留期的层级。
 */
fun Memory.shouldPromoteTier(accessCount: Int, ageDays: Int): Boolean {
    val tier = inferMemoryTier()
    return when (tier) {
        MemoryTier.WORKING -> accessCount > 3 || ageDays > 0
        MemoryTier.EPISODIC -> accessCount > 10 || ageDays > 7
        MemoryTier.SEMANTIC -> false
    }
}
