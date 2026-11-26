# Adapt the pinned desktop file loaders to the Android APK file descriptor.
function(replace_loader_text variable old new)
    string(FIND "${${variable}}" "${old}" offset)
    if(offset EQUAL -1)
        message(FATAL_ERROR "Pinned Qwen source no longer matches the Android loader near: ${old}")
    endif()
    string(REPLACE "${old}" "${new}" result "${${variable}}")
    set(${variable} "${result}" PARENT_SCOPE)
endfunction()

file(READ "${qwentts_SOURCE_DIR}/src/gguf-weights.h" weights)
replace_loader_text(weights "    int fd;" "    CuteDogAssetMap apk_asset;")
replace_loader_text(weights [=[    if (gf->mapping) {
        munmap(gf->mapping, gf->file_size);
    }
    if (gf->fd >= 0) {
        close(gf->fd);
    }]=] "    cutedog_unmap_asset(&gf->apk_asset);")
string(FIND "${weights}" "    // mmap the file" mmap_start)
string(FIND "${weights}" "    // Parse GGUF header" mmap_end)
if(mmap_start EQUAL -1 OR mmap_end LESS_EQUAL mmap_start)
    message(FATAL_ERROR "Pinned GGUF mmap loader changed")
endif()
string(SUBSTRING "${weights}" 0 ${mmap_start} before)
string(SUBSTRING "${weights}" ${mmap_end} -1 after)
set(weights "${before}    if (!cutedog_map_asset(path, &gf->apk_asset)) return false;
    gf->mapping = gf->apk_asset.data;
    gf->file_size = gf->apk_asset.size;
${after}")
replace_loader_text(weights "gguf_init_from_file(path, params)" "gguf_init_from_buffer(gf->mapping, gf->file_size, params)")
file(WRITE "${QWEN_SRC}/gguf-weights.h" "#include \"cutedog_assets.h\"\n${weights}")

file(READ "${qwentts_SOURCE_DIR}/src/bpe.h" tokenizer)
replace_loader_text(tokenizer "gguf_init_from_file(gguf_path, gp)" "cutedog_read_metadata(gguf_path, gp)")
file(WRITE "${QWEN_SRC}/bpe.h" "#include \"cutedog_assets.h\"\n${tokenizer}")

file(READ "${qwentts_SOURCE_DIR}/src/pipeline-codec.cpp" codec)
replace_loader_text(codec "bool pipeline_codec_stream_restore(" "static bool restore_cached_codec_state(")
file(WRITE "${QWEN_SRC}/pipeline-codec.cpp" "${codec}\n" [=[
#include "a_codec_state.h"
bool pipeline_codec_stream_restore(PipelineCodec *pc, uint64_t key, int set) {
    return restore_cached_codec_state(pc, key, set) || cutedog_restore_a_state(pc, key, set);
}
]=])
