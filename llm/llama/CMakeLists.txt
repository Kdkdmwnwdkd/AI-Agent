cmake_minimum_required(VERSION 3.22.1)

project("llama_jni")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/operit_git_source.cmake")

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# 启用 Vulkan GPU 后端，支持 Adreno GPU 层 offload（魅族20/骁龙8Gen2 Adreno740）。
# 选定 CMake ≥ 3.24 是因为 llama.cpp 720d7fa 的 ggml-vulkan/CMakeLists.txt 硬性要求
# find_package(Vulkan COMPONENTS glslc REQUIRED)，而 glslc 组件语法自 CMake 3.24 才
# 进入 FindVulkan 模块。CI 与本地构建均使用 CMake 3.31.6（见 build.gradle.kts 与
# .github/workflows/android-build.yml 的 cmake.dir 配置）。
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(LLAMA_VULKAN ON CACHE BOOL "" FORCE)

# 手动指定 glslc 路径（用于 llama.cpp Vulkan 着色器编译）。
# 交叉编译时 CMAKE_FIND_ROOT_PATH_MODE_PROGRAM=ONLY 会限制搜索，需临时放宽。
# CMake 3.24+ 的 FindVulkan glslc 组件会读取 Vulkan_GLSLC_EXECUTABLE 变量。
set(_old_find_root_path_mode_program "${CMAKE_FIND_ROOT_PATH_MODE_PROGRAM}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
find_program(Vulkan_GLSLC_EXECUTABLE
    NAMES glslc
    HINTS
        "${ANDROID_NDK}/shader-tools"
        "${CMAKE_ANDROID_NDK}/shader-tools"
        "$ENV{ANDROID_NDK_HOME}/shader-tools"
        "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
        /usr/bin
        /usr/local/bin
)
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM "${_old_find_root_path_mode_program}")
if(Vulkan_GLSLC_EXECUTABLE)
    set(Vulkan_GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
    message(STATUS "Found glslc: ${Vulkan_GLSLC_EXECUTABLE}")
else()
    message(WARNING "glslc not found, Vulkan shaders will fail to compile")
    message(STATUS "ANDROID_NDK=${ANDROID_NDK} CMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}")
endif()

# Vulkan 头文件：Android NDK 自带的 vulkan.h 版本较旧，与 Vulkan-Hpp 不匹配。
# 拉取配套版本的 Vulkan-Headers + Vulkan-Hpp，用 BEFORE 让它们优先于 NDK 自带的 vulkan.h。
# 三者对应同一个 Vulkan SDK 1.3.275 版本（VK_HEADER_VERSION 275），Khronos 同步发布的稳定配套版本。
# 注意各仓库 tag 命名不同：Vulkan-Headers/SPIRV-Headers 用 vulkan-sdk-1.3.275.0，
# Vulkan-Hpp 用 v1.3.275。
# Vulkan-Hpp header 最低要求 C++11，spaceship operator 等特性为条件编译，
# 在 C++17（NDK 25 clang 14）下自动走 #else 分支，无需额外兼容宏。
operit_prepare_git_source(
    OPERIT_VULKAN_HEADERS_SOURCE_DIR
    OPERIT_VULKAN_HEADERS_BINARY_DIR
    vulkan_headers
    "https://github.com/KhronosGroup/Vulkan-Headers.git"
    "vulkan-sdk-1.3.275.0"
)
operit_prepare_git_source(
    OPERIT_VULKAN_HPP_SOURCE_DIR
    OPERIT_VULKAN_HPP_BINARY_DIR
    vulkan_hpp
    "https://github.com/KhronosGroup/Vulkan-Hpp.git"
    "v1.3.275"
)
include_directories(BEFORE "${OPERIT_VULKAN_HEADERS_SOURCE_DIR}/include")
include_directories(BEFORE "${OPERIT_VULKAN_HPP_SOURCE_DIR}")

# SPIRV-Headers：llama.cpp 的 Vulkan 后端依赖它，但 Android NDK 不提供 CMake config。
# 该 tag 的源码只包含 SPIRV-HeadersConfig.cmake.in 模板，未 install 时无可用 config 文件，
# 因此手写一份指向源码 include 目录的 config，让 find_package(SPIRV-Headers CONFIG) 能找到。
operit_prepare_git_source(
    OPERIT_SPIRV_HEADERS_SOURCE_DIR
    OPERIT_SPIRV_HEADERS_BINARY_DIR
    spirv_headers
    "https://github.com/KhronosGroup/SPIRV-Headers.git"
    "vulkan-sdk-1.3.275.0"
)

# 生成 SPIRV-HeadersConfig.cmake，让 find_package(SPIRV-Headers CONFIG) 能找到
set(SPIRV_HEADERS_CONFIG_DIR "${OPERIT_SPIRV_HEADERS_BINARY_DIR}/cmake")
file(MAKE_DIRECTORY "${SPIRV_HEADERS_CONFIG_DIR}")
file(WRITE "${SPIRV_HEADERS_CONFIG_DIR}/SPIRV-HeadersConfig.cmake"
"set(SPIRV-Headers_INCLUDE_DIR \"${OPERIT_SPIRV_HEADERS_SOURCE_DIR}/include\")
set(SPIRV-Headers_INCLUDE_DIRS \"${OPERIT_SPIRV_HEADERS_SOURCE_DIR}/include\")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES \"${OPERIT_SPIRV_HEADERS_SOURCE_DIR}/include\"
    )
endif()
set(SPIRV-Headers_FOUND TRUE)
")
set(SPIRV-Headers_DIR "${SPIRV_HEADERS_CONFIG_DIR}" CACHE PATH "" FORCE)
# llama.cpp 720d7fa 的 ggml-vulkan 未 link SPIRV-Headers::SPIRV-Headers target，
# 但其 ggml-vulkan.cpp 直接 #include <spirv/unified1/spirv.hpp>，
# 因此需将 SPIRV-Headers 的 include 目录加入全局 include path。
include_directories("${OPERIT_SPIRV_HEADERS_SOURCE_DIR}/include")

operit_prepare_git_source(
    OPERIT_LLAMA_CPP_SOURCE_DIR
    OPERIT_LLAMA_CPP_BINARY_DIR
    llama_cpp
    "https://github.com/ggml-org/llama.cpp.git"
    "720d7fa4097f76e5d0eade5a92c1df87c1faf9d9"
)

add_subdirectory("${OPERIT_LLAMA_CPP_SOURCE_DIR}" "${OPERIT_LLAMA_CPP_BINARY_DIR}" EXCLUDE_FROM_ALL)

add_library(
    LlamaWrapper
    SHARED
    src/main/cpp/llama_jni_stub.cpp
)

target_compile_definitions(LlamaWrapper PRIVATE OPERIT_HAS_LLAMA_CPP=1)
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/common")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/include")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/ggml/include")
target_include_directories(LlamaWrapper PRIVATE "${OPERIT_LLAMA_CPP_SOURCE_DIR}/vendor")
target_link_libraries(LlamaWrapper llama llama-common)

target_link_libraries(
    LlamaWrapper
    android
    log
    vulkan
)

# 16KB page size support (Android 15+ requirement)
target_link_options(LlamaWrapper PRIVATE "-Wl,-z,max-page-size=16384")
