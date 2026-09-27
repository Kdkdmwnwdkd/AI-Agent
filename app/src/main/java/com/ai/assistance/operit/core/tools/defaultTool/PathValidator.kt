package com.ai.assistance.operit.core.tools.defaultTool

import com.ai.assistance.operit.core.tools.FileOperationData
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.StringResultData

object PathValidator {
    fun validateAndroidPath(path: String, toolName: String, paramName: String = "path"): ToolResult? {
        if (path.isBlank()) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "$paramName parameter is required"
            )
        }
        if (!path.startsWith("/")) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = FileOperationData(
                    operation = toolName,
                    path = path,
                    successful = false,
                    details = "Invalid path: '$path'. Path must be an absolute path starting with '/'."
                ),
                error = "Invalid path: '$path'. Path must be an absolute path starting with '/'."
            )
        }
        if (containsTraversal(path)) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = FileOperationData(
                    operation = toolName,
                    path = path,
                    successful = false,
                    details = "Invalid path: '$path'. Path traversal ('..') is not allowed."
                ),
                error = "Invalid path: '$path'. Path traversal ('..') is not allowed."
            )
        }
        return null
    }

    fun validateLinuxPath(path: String, toolName: String, paramName: String = "path"): ToolResult? {
        if (path.isBlank()) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = StringResultData(""),
                error = "$paramName parameter is required"
            )
        }
        if (!path.startsWith("/") && !path.startsWith("~")) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = FileOperationData(
                    operation = toolName,
                    env = "linux",
                    path = path,
                    successful = false,
                    details = "Invalid path: '$path'. Path must start with '/' or '~'."
                ),
                error = "Invalid path: '$path'. Path must start with '/' or '~'."
            )
        }
        if (containsTraversal(path)) {
            return ToolResult(
                toolName = toolName,
                success = false,
                result = FileOperationData(
                    operation = toolName,
                    env = "linux",
                    path = path,
                    successful = false,
                    details = "Invalid path: '$path'. Path traversal ('..') is not allowed."
                ),
                error = "Invalid path: '$path'. Path traversal ('..') is not allowed."
            )
        }
        return null
    }

    /** 检测路径穿越：拒绝 ".." 段与反斜杠（防止越出允许目录） */
    private fun containsTraversal(path: String): Boolean {
        if (path.contains('\\')) return true
        return path.split('/').any { it == ".." }
    }
}
