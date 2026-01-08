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
