cmake_minimum_required(VERSION 3.22.1)

project("yomidroid_llm" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# llama.cpp source directory
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)

# Disable features we don't need
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Build llama.cpp
add_subdirectory(${LLAMA_SRC} build-llama)

# JNI bridge library
add_library(${CMAKE_PROJECT_NAME} SHARED
    llm_inference.cpp
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    llama
    common
    android
    log
)

# ---------------------------------------------------------------------------
# Hoshidicts dictionary backend (C++23) + JNI bridge.
#
# Hoshidicts' own CMakeLists builds the static `hoshidicts` lib plus a CLI and
# benchmark executables. We only want the lib, so we pull it in via
# add_subdirectory (which also wires glaze/zstd/libdeflate/unordered_dense) and
# restrict the targets Gradle actually compiles via android.defaultConfig
# .externalNativeBuild.cmake.targets — the executables are configured but never
# built. Force the hoshidicts lib + its deps static so we ship one extra .so
# (yomidroid_dict) rather than a chain of shared libs; llama above already
# enabled BUILD_SHARED_LIBS globally, so we shadow it for this subtree only.
set(HOSHI_SRC ${CMAKE_CURRENT_LIST_DIR}/hoshidicts)
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${HOSHI_SRC} build-hoshidicts)

# NDK 26.1's libc++ (LLVM 17) lacks std::ranges::to. Force-include a guarded
# shim into the hoshidicts sources without modifying the vendored submodule.
target_compile_options(hoshidicts PRIVATE
    -include ${CMAKE_CURRENT_LIST_DIR}/hoshi_compat.hpp
)

add_library(yomidroid_dict SHARED
    dict_jni.cpp
)

# The bridge calls C++23 ranges in the hoshidicts headers transitively; compile
# it at the same standard as the library it links.
set_target_properties(yomidroid_dict PROPERTIES
    CXX_STANDARD 23
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(yomidroid_dict PRIVATE
    ${HOSHI_SRC}/include
    ${HOSHI_SRC}/external/utfcpp/source
)

target_link_libraries(yomidroid_dict
    hoshidicts
    android
    log
)
