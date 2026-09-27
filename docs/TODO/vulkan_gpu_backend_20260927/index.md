---
For_Agent: 正面适配 Vulkan GPU 后端，删除前序对话留下的回退/兜底注释，升级 CMake 到 3.31.6
fork: https://github.com/Kdkdmwnwdkd/AI-Agent
---

# Vulkan GPU 后端正面适配

## 原本状况

前序对话在提交 `b61241e fix(llama): disable Vulkan backend to restore build stability` 中为了恢复 CI 编译，关闭了 Vulkan GPU 后端，并在 [llm/llama/CMakeLists.txt](../../../llm/llama/CMakeLists.txt) 留下「暂时关闭」「已有兜底」「后续单独适配时再打开」等注释。这违反 [AGENTS.md](../../../AGENTS.md) 的硬规则：

- 除非用户要求，禁止写一切的回退代码
- 严令禁止各种回退逻辑，包括「xxx才会退回」「降级处理」「如果没有 就要加 fallback」等字眼

三个新工具 `run_code` / `file_tree` / `web_search` 已保留，未受影响。

## 真正根因

llama.cpp 720d7fa 的 `ggml/src/ggml-vulkan/CMakeLists.txt` 硬性要求：

```cmake
find_package(Vulkan COMPONENTS glslc REQUIRED)
```

而项目锁定的 CMake 3.22.1 的 FindVulkan 模块不支持 `glslc` 组件语法（该组件自 CMake 3.24 才进入 FindVulkan）。`REQUIRED` 组件检查直接失败，导致 native 编译报错，与工具链版本无关。前序对话多次尝试（手动 `find_program`、回退工具链）均未触及根因。

## 本次意图

按 AGENTS.md 要求正面适配，不再回退：

- 升级 CMake 到 3.31.6（≥ 3.24，支持 FindVulkan glslc 组件）
- NDK 版本锁定为 27.0.12077973（与 CI runner SDK 预装版本一致，避免 [CXX1104] 冲突）
- 重新打开 `GGML_VULKAN ON` / `LLAMA_VULKAN ON`
- 删除违规的回退/兜底注释
- 将 Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers 三个依赖从 `main` 分支固定到对应 Vulkan SDK 1.3.275 的稳定 tag（Khronos 同步发布的配套版本，对应 VK_HEADER_VERSION 275）。注意各仓库 tag 命名不同：Vulkan-Headers / SPIRV-Headers 用 `vulkan-sdk-1.3.275.0`，Vulkan-Hpp 用 `v1.3.275`
- CI 通过 `local.properties` 的 `cmake.dir` 复用 ubuntu-24.04 runner 系统预装的 CMake 3.31.6（sdkmanager 仓库无 3.24.x / 3.31.6 精确 micro 版本）

## 预期结果

- CI 构建通过，APK 重新包含 Vulkan 后端 native 库
- 魅睿20（骁龙8Gen2 / Adreno740）等设备可使用 GPU 层 offload
- 三个新工具 `run_code` / `file_tree` / `web_search` 继续保留

## 作用域

- `llm/llama/CMakeLists.txt`：删除违规注释，重新打开 Vulkan，固定三个依赖 tag
- `llm/llama/build.gradle.kts`：CMake `version` 3.22.1 → 3.31.6
- `avator/fbx/build.gradle.kts`、`avator/mmd/build.gradle.kts`、`avator/dragonbones/build.gradle.kts`、`llm/mnn/build.gradle.kts`：CMake `version` 对齐到 3.31.6（AGP 用一个 cmake 编译所有 native 模块，版本须一致）
- `.github/workflows/android-build.yml`、`.github/workflows/pr-check.yml`、`.github/workflows/android-tests.yml`：删除 sdkmanager 装 cmake 步骤，Configure 步骤增加 `cmake.dir` 指向系统预装 cmake
- `docs/doc-src/dev-core/BUILDING.md`：补充本地构建 CMake 安装说明
- `docs/TODO/vulkan_gpu_backend_20260927/`：本计划文档

详见 [1_cmake_upgrade_and_vulkan_enablement.md](1_cmake_upgrade_and_vulkan_enablement.md)。

## 状态

[DONE]
