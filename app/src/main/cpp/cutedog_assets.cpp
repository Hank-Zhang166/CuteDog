#include "cutedog_assets.h"
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <limits>
#include <mutex>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
std::mutex manager_lock;
// JNI retains the application AssetManager while native model initialisation uses its APK.
jobject manager_ref = nullptr;
AAssetManager *assets = nullptr;
}

bool cutedog_set_assets(JNIEnv *env, jobject manager) {
    if (!manager) return false;
    std::lock_guard<std::mutex> guard(manager_lock);
    if (manager_ref && env->IsSameObject(manager_ref, manager)) return true;
    auto *native_manager = AAssetManager_fromJava(env, manager);
    if (!native_manager) return false;
    auto keep = env->NewGlobalRef(manager);
    if (!keep) return false;
    if (manager_ref) env->DeleteGlobalRef(manager_ref);
    manager_ref = keep;
    assets = native_manager;
    return true;
}

bool cutedog_map_asset(const char *path, CuteDogAssetMap *out) {
    *out = {};
    off64_t start = 0, length = 0;
    int fd = -1;
    {
        std::lock_guard<std::mutex> guard(manager_lock);
        AAsset *asset = assets ? AAssetManager_open(assets, path, AASSET_MODE_RANDOM) : nullptr;
        if (!asset) return false;
        fd = AAsset_openFileDescriptor64(asset, &start, &length);
        AAsset_close(asset);
    }
    if (fd < 0) return false;
    const long page = sysconf(_SC_PAGESIZE);
    struct stat st{};
    if (start < 0 || length <= 0 || page <= 0 || fstat(fd, &st) != 0 ||
        start > st.st_size || length > st.st_size - start) {
        close(fd);
        return false;
    }
    // ZIP alignment and OS page alignment are independent, including on 16 KiB devices.
    const auto prefix = static_cast<size_t>(start % page);
    if (static_cast<uint64_t>(length) > std::numeric_limits<size_t>::max() - prefix) {
        close(fd);
        return false;
    }
    const size_t size = static_cast<size_t>(length);
    const size_t mapped_size = size + prefix;
    void *base = mmap(nullptr, mapped_size, PROT_READ, MAP_PRIVATE, fd, start - static_cast<off64_t>(prefix));
    close(fd);
    if (base == MAP_FAILED) return false;
    *out = {base, mapped_size, static_cast<uint8_t *>(base) + prefix, size};
    return true;
}

void cutedog_unmap_asset(CuteDogAssetMap *map) {
    if (map->base) munmap(map->base, map->mapped_size);
    *map = {};
}

gguf_context *cutedog_read_metadata(const char *path, gguf_init_params params) {
    // The tokenizer loaders request metadata only. gguf owns the resulting metadata.
    if (!params.no_alloc) return nullptr;
    CuteDogAssetMap map{};
    if (!cutedog_map_asset(path, &map)) return nullptr;
    auto *ctx = gguf_init_from_buffer(map.data, map.size, params);
    cutedog_unmap_asset(&map);
    return ctx;
}
