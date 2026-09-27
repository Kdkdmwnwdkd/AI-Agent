---
For_Agent: CMake 升级到 3.31.6 与 Vulkan 后端重新启用的技术记录
---

# CMake 升级与 Vulkan 后端启用

## 根因复核

llama.cpp 720d7fa 的 Vulkan 后端在 `ggml/src/ggml-vulkan/CMakeLists.txt` 第 11 行硬性要求：

```cmake
find_package(Vulkan COMPONENTS glslc REQUIRED)
```

`glslc` 组件语法自 CMake 3.24 才进入 FindVulkan 模块（见 [CMake 3.24 Release Notes](https://cmake.org/cmake/help/v3.25/release/3.24.html) 与 [FindVulkan 文档](https://cmake.org/cmake/help/v3.24/module/FindVulkan.html)）。项目原锁定的 CMake 3.22.1 的 FindVulkan 不识别 `glslc` 组件，`REQUIRED` 检查直接失败，导致 native 编译报错。前序对话多次尝试（手动 `find_program`、回退工具链）均未触及根因，最终在 `b61241e` 关闭 Vulkan 以恢复编译，但留下违反 AGENTS.md 的回退/兜底注释。

## 改动详情

### CMake 版本升级到 3.31.6

- [llm/llama/build.gradle.kts](../../../llm/llama/build.gradle.kts) 的 `externalNativeBuild.cmake.version` 由 `3.22.1` 改为 `3.31.6`
- 其余 4 个 native 模块（`avator/fbx`、`avator/mmd`、`avator/dragonbones`、`llm/mnn`）的 `version` 字段同步改为 `3.31.6`，因为 AGP 用一个 cmake 编译所有 native 模块，版本必须一致

### CI 改用系统预装 CMake

sdkmanager 仓库不含 3.24.x / 3.31.6 精确 micro 版本，因此 CI 不再通过 `sdkmanager "cmake;..."` 安装，改为复用 ubuntu-24.04 runner 系统预装的 CMake 3.31.6（≥ 3.24，支持 FindVulkan glslc 组件）：

- `ANDROID_CMAKE_VERSION` 环境变量改为 `3.31.6`（用于缓存 key 与文档）
- Install 步骤删除 `cmake;$ANDROID_CMAKE_VERSION` 行
- Configure 步骤增加 `cmake.dir` 写入 `local.properties`，通过 `readlink -f "$(command -v cmake)"` 解析符号链接后取祖父目录（即包含 `bin/cmake` 的目录的父目录）

涉及三个 workflow：`android-build.yml`、`pr-check.yml`（含两处 Configure 步骤）、`android-tests.yml`。

### NDK 版本锁定为 27.0.12077973

CI runner（ubuntu-24.04）的 Android SDK 预装了 NDK 27.0.12077973。AGP 在未显式设置 `ndkVersion` 时会自动选中 SDK 里最新的 NDK（即 27），若与 `local.properties` 的 `ndk.dir` 不一致则报 `[CXX1104]`。为避免该冲突：

- [app/build.gradle.kts](../../../app/build.gradle.kts) 显式设置 `ndkVersion = "27.0.12077973"`，与 SDK 预装版本一致
- CI 不再设置 `ndk.dir`，由 AGP 自动定位 SDK 内的 NDK 27
- CI 的 `ANDROID_NDK_VERSION` 环境变量同步改为 `27.0.12077973`

### Vulkan 后端重新启用

[llm/llama/CMakeLists.txt](../../../llm/llama/CMakeLists.txt)：

- 删除前序对话的「暂时关闭」「已有兜底」「后续单独适配」等违规注释，替换为正面说明（解释为何选 CMake ≥ 3.24）
- 重新 `set(GGML_VULKAN ON)` / `set(LLAMA_VULKAN ON)`
- 保留 glslc 的 `find_program` workaround（交叉编译时 `CMAKE_FIND_ROOT_PATH_MODE_PROGRAM=ONLY` 会限制搜索，且 CMake 3.24+ 的 FindVulkan glslc 组件会读取 `Vulkan_GLSLC_EXECUTABLE` 变量）
- Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers 三个依赖的 git ref 由 `main` 改为对应 Vulkan SDK 1.3.275 的稳定 tag。各仓库 tag 命名不同：Vulkan-Headers / SPIRV-Headers 用 `vulkan-sdk-1.3.275.0`，Vulkan-Hpp 用 `v1.3.275`
- 保留手写的 `SPIRV-HeadersConfig.cmake`（该 tag 源码仅含 `.in` 模板，未 install 时无可用 config 文件）
- `target_link_libraries` 加回 `vulkan`

### Vulkan-Hpp C++17 兼容性

调研确认 Vulkan-Hpp header 最低要求 C++11（见 [Vulkan-Hpp Building.md](https://github.com/KhronosGroup/Vulkan-Hpp/blob/main/docs/Building.md)），spaceship operator 等特性为条件编译，在 C++17（NDK 27 clang）下自动走 `#else` 分支，无需额外兼容宏。因此 CMakeLists.txt 不引入任何 `VULKAN_HPP_NO_*` 定义。

### Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers tag 选择

三者统一固定到 Vulkan SDK 1.3.275 对应的稳定 tag：

- 该版本对应 VK_HEADER_VERSION 275，是 Khronos 同步发布的稳定配套版本
- 各仓库 tag 命名不同：Vulkan-Headers / SPIRV-Headers 用 `vulkan-sdk-1.3.275.0`，Vulkan-Hpp 用 `v1.3.275`
- 1.3 系列与 llama.cpp 720d7fa（2024 年初版本）时间线匹配
- 三者版本一致，互相兼容，避免 `main` 分支漂移
- `include_directories(BEFORE ...)` 让拉取的头文件优先于 NDK 自带的 vulkan.h

## 验证方式

本次改动需通过 CI 构建验证（AGENTS.md：默认不执行编译/构建/测试命令，仅在用户明确要求时执行）。推送后 `Android Build` 与 `Android Tests` workflow 会自动触发，关注：

- CMake 配置阶段 `find_package(Vulkan COMPONENTS glslc REQUIRED)` 不再报错
- glslc 路径正确指向 NDK 的 shader-tools 或系统 glslc
- Vulkan-Headers / Vulkan-Hpp / SPIRV-Headers 三个 FetchContent 成功拉取 `vulkan-sdk-1.3.275.0` tag
- native 编译产出包含 Vulkan 后端代码
- APK 产出成功

[DONE]
