cmake_minimum_required(VERSION 3.22)
project(cutedog_voice LANGUAGES C CXX)
include(FetchContent)

# Pinned source archives for the Android CPU inference library.
FetchContent_Declare(qwentts URL https://codeload.github.com/ServeurpersoCom/qwentts.cpp/tar.gz/2edb35f87b709a070b2d5379f77e5c52a1daa585
    URL_HASH SHA256=5cd0763f19ad247c84aac76344cd27269bdbdae00b4f9adae280e0208919a892)
FetchContent_Declare(ggml URL https://codeload.github.com/ServeurpersoCom/ggml/tar.gz/ef4555b7aede4467a5de803b103615e0a5f30dd8
    URL_HASH SHA256=5e9c6bc3f06804d437d1ff4cbc5070593de3f3e0a25bd9dd34029e28e94a4c6d)
FetchContent_Declare(sonic URL https://codeload.github.com/waywardgeek/sonic/tar.gz/b93885dcb70aae50c6f76b0fe4e0868f029a077e
    URL_HASH SHA256=b76d832649306b53e716c271014c0b8f89f6bcefc43e5068a2eca3cf1946324c)
FetchContent_GetProperties(sonic)
if(NOT sonic_POPULATED)
    FetchContent_Populate(sonic)
endif()
FetchContent_GetProperties(qwentts)
if(NOT qwentts_POPULATED)
    FetchContent_Populate(qwentts)
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# APK previews use the debug Android variant. Neural kernels still need normal optimisation.
add_compile_options(-O3)
set(BUILD_SHARED_LIBS OFF)
set(GGML_NATIVE OFF CACHE BOOL "")
set(GGML_OPENMP OFF CACHE BOOL "")
set(GGML_BACKEND_DL OFF CACHE BOOL "")
set(GGML_BUILD_TESTS OFF CACHE BOOL "")
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "")
set(GGML_LLAMAFILE OFF CACHE BOOL "")
set(GGML_AVX OFF CACHE BOOL "")
set(GGML_AVX2 OFF CACHE BOOL "")
set(GGML_FMA OFF CACHE BOOL "")
set(GGML_F16C OFF CACHE BOOL "")
set(GGML_BMI2 OFF CACHE BOOL "")
add_compile_definitions(GGML_MAX_NAME=128)
FetchContent_MakeAvailable(ggml)
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/version.h" "#pragma once\n#define QWEN_VERSION \"2edb35f87b70\"\n")

set(QWEN_SRC "${CMAKE_CURRENT_BINARY_DIR}/qwen-mobile/src")
file(COPY "${qwentts_SOURCE_DIR}/src" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/qwen-mobile")
include("${CMAKE_CURRENT_SOURCE_DIR}/apk_assets.cmake")
# The app's 58 UTF-16 unit chunks, fixed prompt, and at most 443 codec frames fit in 1024 tokens.
# This reduces the talker KV cache from 896 to 224 MiB without changing weights.
file(READ "${QWEN_SRC}/pipeline-tts.cpp" pipeline)
string(REPLACE "pt->talker.head_dim, 4096, pt->max_batch" "pt->talker.head_dim, 1024, pt->max_batch" pipeline "${pipeline}")
string(REPLACE "pt->has_speaker_encoder = (pt->model_type == \"base\");" "pt->has_speaker_encoder = false;" pipeline "${pipeline}")
file(WRITE "${QWEN_SRC}/pipeline-tts.cpp" "${pipeline}")
# Android CPU cores and emulator vCPUs are already independently schedulable.
file(READ "${QWEN_SRC}/backend.h" backend)
string(REPLACE "std::thread::hardware_concurrency() / 2" "std::thread::hardware_concurrency()" backend "${backend}")
string(REPLACE "return n > 0 ? n : 1;" "return n > 4 ? 4 : (n > 0 ? n : 1);" backend "${backend}")
file(WRITE "${QWEN_SRC}/backend.h" "${backend}")
add_library(cutedog_voice SHARED pet_voice.cpp cutedog_assets.cpp
    "${sonic_SOURCE_DIR}/sonic.c"
    "${QWEN_SRC}/qwen.cpp"
    "${QWEN_SRC}/pipeline-tts.cpp"
    "${QWEN_SRC}/pipeline-codec.cpp")
target_include_directories(cutedog_voice PRIVATE "${QWEN_SRC}" "${sonic_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(cutedog_voice PRIVATE ggml ggml-cpu ggml-base log android)
target_link_options(cutedog_voice PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
