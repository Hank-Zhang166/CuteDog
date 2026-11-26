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
add_compile_definitions(NDEBUG)
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
# Prime exactly the same fixed-voice decoder snapshot that live synthesis restores.
file(APPEND "${QWEN_SRC}/pipeline-tts.cpp" [=[
bool cutedog_prime_codec(PipelineTTS *pt, const int32_t *codes, int frames) {
    TtsEngine staging{};
    staging.pt = pt;
    TtsSlot voice{};
    voice.ref_codes_ptr = codes;
    voice.ref_codes_T = frames;
    return tts_engine_codec_admit(&staging, &voice);
}
]=])
file(READ "${qwentts_SOURCE_DIR}/src/qwen.cpp" facade)
string(REPLACE "struct qt_context {" [=[struct qt_context {
    ggml_threadpool_t mobile_cpu_pool = nullptr;
    ~qt_context() { if (mobile_cpu_pool) ggml_threadpool_free(mobile_cpu_pool); }
]=] facade "${facade}")
file(WRITE "${QWEN_SRC}/qwen.cpp" "#include \"ggml-cpu.h\"\n${facade}\n" [=[
void cutedog_bind_cpu_pool(qt_context *q) {
    if (!q || !q->bp.cpu_backend) return;
    auto config = ggml_threadpool_params_default(backend_cpu_n_threads());
    config.poll = 20;
    q->mobile_cpu_pool = ggml_threadpool_new(&config);
    if (q->mobile_cpu_pool)
        ggml_backend_cpu_set_threadpool(q->bp.cpu_backend, q->mobile_cpu_pool);
}
bool cutedog_prime_codec(PipelineTTS *, const int32_t *, int);
bool cutedog_prime_voice(qt_context *q, const int32_t *codes, int frames) {
    return q && cutedog_prime_codec(&q->pt, codes, frames);
}
]=])
# Android CPU cores and emulator vCPUs are already independently schedulable.
file(READ "${QWEN_SRC}/backend.h" backend)
string(REPLACE "std::thread::hardware_concurrency() / 2" "std::thread::hardware_concurrency()" backend "${backend}")
string(REPLACE "return n > 0 ? n : 1;" "return n > 4 ? 4 : (n > 0 ? n : 1);" backend "${backend}")
file(WRITE "${QWEN_SRC}/backend.h" "${backend}")
add_library(cutedog_cpu SHARED cpu_dispatch.cpp)
target_link_options(cutedog_cpu PRIVATE "-Wl,-z,max-page-size=16384")
add_library(cutedog_synthesis OBJECT pet_voice.cpp cutedog_assets.cpp a_codec_state.cpp
    "${sonic_SOURCE_DIR}/sonic.c"
    "${QWEN_SRC}/qwen.cpp"
    "${QWEN_SRC}/pipeline-tts.cpp"
    "${QWEN_SRC}/pipeline-codec.cpp")
target_include_directories(cutedog_synthesis PRIVATE "${QWEN_SRC}" "${sonic_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}" "${ggml_SOURCE_DIR}/include")

# Only the CPU kernels vary. Each selected JNI library resolves ggml's static
# registry to precisely one kernel archive, so older CPUs retain the base ABI.
function(cutedog_engine name kernels)
    add_library(${name} SHARED $<TARGET_OBJECTS:cutedog_synthesis>)
    add_dependencies(${name} ggml)
    target_link_libraries(${name} PRIVATE "$<TARGET_FILE:ggml>" ${kernels} ggml-base log android dl z)
    target_link_options(${name} PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,-z,max-page-size=16384")
endfunction()
cutedog_engine(cutedog_voice ggml-cpu)

function(cutedog_fast_engine suffix)
    get_target_property(kernel_root ggml-cpu SOURCE_DIR)
    get_target_property(sources ggml-cpu SOURCES)
    list(TRANSFORM sources PREPEND "${kernel_root}/")
    set(kernels cutedog_kernels_${suffix})
    add_library(${kernels} STATIC ${sources} "${kernel_root}/ggml-cpu/llamafile/sgemm.cpp")
    target_include_directories(${kernels} PRIVATE $<TARGET_PROPERTY:ggml-cpu,INCLUDE_DIRECTORIES>)
    target_compile_definitions(${kernels} PRIVATE $<TARGET_PROPERTY:ggml-cpu,COMPILE_DEFINITIONS> GGML_USE_LLAMAFILE)
    target_link_libraries(${kernels} PRIVATE ggml-base)
    if(ANDROID_ABI STREQUAL "x86_64")
        target_compile_options(${kernels} PRIVATE -msse4.2 -mavx -mavx2 -mfma -mf16c -mbmi2)
        target_compile_definitions(${kernels} PRIVATE GGML_SSE42 GGML_AVX GGML_AVX2 GGML_FMA GGML_F16C GGML_BMI2)
    elseif(suffix STREQUAL "i8mm")
        target_compile_options(${kernels} PRIVATE -march=armv8.2-a+fp16+dotprod+i8mm)
    else()
        target_compile_options(${kernels} PRIVATE -march=armv8.2-a+fp16+dotprod)
    endif()
    cutedog_engine(cutedog_voice_${suffix} ${kernels})
endfunction()
cutedog_fast_engine(fast)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    cutedog_fast_engine(i8mm)
endif()
