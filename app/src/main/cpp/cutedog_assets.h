#pragma once

#include <jni.h>
#include <cstddef>
#include <cstdint>
#include "gguf.h"

struct CuteDogAssetMap {
    void *base;
    size_t mapped_size;
    uint8_t *data;
    size_t size;
};

bool cutedog_set_assets(JNIEnv *env, jobject manager);
bool cutedog_map_asset(const char *path, CuteDogAssetMap *out);
void cutedog_unmap_asset(CuteDogAssetMap *map);
gguf_context *cutedog_read_metadata(const char *path, gguf_init_params params);
