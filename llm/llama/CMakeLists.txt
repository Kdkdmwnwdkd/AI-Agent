cmake_minimum_required(VERSION 3.22.1)

project("llama_jni")

include("${CMAKE_CURRENT_LIST_DIR}/../../cmake/operit_git_source.cmake")

set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# 启用 Vulkan GPU 后端，支持 Adreno GPU 层 offload（魅族20/骁龙8Gen2 Adreno740）
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(LLAMA_VULKAN ON CACHE BOOL "" FORCE)

# 手动指定 glslc 路径（Android NDK 自带，CMake 的 find_package(Vulkan COMPONENTS glslc) 找不到）
# 交叉编译时需要 NO_CMAKE_FIND_ROOT_PATH 才能在宿主机路径中搜索
find_program(Vulkan_GLSLC_EXECUTABLE
    NAMES glslc
    HINTS
        "${ANDROID_NDK}/shader-tools"
        "${CMAKE_ANDROID_NDK}/shader-tools"
        "$ENV{ANDROID_NDK_HOME}/shader-tools"
        "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
        "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin"
    NO_CMAKE_FIND_ROOT_PATH
)
if(Vulkan_GLSLC_EXECUTABLE)
    set(Vulkan_GLSLC_EXECUTABLE "${Vulkan_GLSLC_EXECUTABLE}" CACHE FILEPATH "" FORCE)
    message(STATUS "Found glslc: ${Vulkan_GLSLC_EXECUTABLE}")
else()
    message(WARNING "glslc not found, Vulkan shaders will fail to compile")
    message(STATUS "ANDROID_NDK=${ANDROID_NDK} CMAKE_ANDROID_NDK=${CMAKE_ANDROID_NDK}")
endif()

# SPIRV-Headers：llama.cpp 的 Vulkan 后端依赖它，但 Android NDK 不提供 CMake config
operit_prepare_git_source(
    OPERIT_SPIRV_HEADERS_SOURCE_DIR
    OPERIT_SPIRV_HEADERS_BINARY_DIR
    spirv_headers
    "https://github.com/KhronosGroup/SPIRV-Headers.git"
    "main"
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
