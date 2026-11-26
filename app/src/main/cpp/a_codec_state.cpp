// A fixed acoustic reference has the same decoder history before every new reply.
#include "a_codec_state.h"
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>
#include <zlib.h>

namespace {
constexpr uint32_t magic = 0x31414443; // CDA1 on little-endian Android and build hosts
constexpr unsigned reference_frames = 56;
constexpr size_t max_state_bytes = 64 * 1024 * 1024;

std::vector<ggml_tensor *> state_tensors(PipelineCodec *codec, int set) {
    std::vector<ggml_tensor *> tensors;
    for (int i = 0; i < codec->stream_n_state; ++i)
        tensors.push_back(codec->stream_set_views[set * codec->stream_n_state + i]);
    for (int layer = 0; layer < codec->stream_kv.n_layers; ++layer) {
        tensors.push_back(kv_cache_k(&codec->stream_kv, set, layer));
        tensors.push_back(kv_cache_v(&codec->stream_kv, set, layer));
    }
    return tensors;
}
}

#ifdef CUTEDOG_EXPORT_A_STATE
#include "rvq-file.h"
#include <fstream>

// Run against the pinned qwentts.cpp/ggml build using the shipped codec and A.rvq.
int main(int argc, char **argv) {
    if (argc != 4) return 1;
    PipelineCodec codec{};
    backend_load();
    auto backend = backend_init("codec", "CPU");
    if (!backend.backend || !pipeline_codec_load(&codec, argv[1], backend)) return 2;
    constexpr int books = 16;
    std::vector<int32_t> codes;
    int frames = 0;
    if (!rvq_read_file(argv[2], books, 11, codes, &frames) || frames != reference_frames) return 3;
    codec.stream_sets = 2;
    constexpr int staging = 1;
    if (!pipeline_codec_stream_reset(&codec, staging)) return 4;
    for (int start = 0; start < frames;) {
        int block = 1 << (CODEC_STREAM_CLASSES - 1);
        while (block > frames - start) block /= 2;
        std::vector<int32_t> chunk(books * block);
        for (int k = 0; k < books; ++k)
            std::copy_n(codes.data() + k * frames + start, block, chunk.data() + k * block);
        if (!pipeline_codec_decode_stream(&codec, chunk.data(), block, nullptr)) return 5;
        start += block;
    }
    if (codec.stream_pos[staging] != frames) return 6;
    std::vector<uint8_t> raw;
    for (auto *tensor : state_tensors(&codec, staging)) {
        const auto n = ggml_nbytes(tensor);
        const auto offset = raw.size();
        if (offset + n + 4 > max_state_bytes) return 7;
        raw.resize(offset + 4 + n);
        uint32_t length = static_cast<uint32_t>(n);
        std::memcpy(raw.data() + offset, &length, sizeof(length));
        ggml_backend_tensor_get(tensor, raw.data() + offset + 4, 0, n);
    }
    uLongf packed_size = compressBound(raw.size());
    std::vector<uint8_t> packed(packed_size);
    if (compress2(packed.data(), &packed_size, raw.data(), raw.size(), Z_BEST_COMPRESSION) != Z_OK) return 8;
    const uint32_t raw_size = static_cast<uint32_t>(raw.size());
    const uint64_t key = pipeline_codec_ref_key(codes.data(), books, frames);
    std::ofstream out(argv[3], std::ios::binary);
    out.write(reinterpret_cast<const char *>(&magic), 4);
    out.write(reinterpret_cast<const char *>(&raw_size), 4);
    out.write(reinterpret_cast<const char *>(&key), 8);
    out.write(reinterpret_cast<const char *>(packed.data()), packed_size);
    return out.good() ? 0 : 9;
}
#else
#include "cutedog_assets.h"

bool cutedog_restore_a_state(PipelineCodec *codec, uint64_t key, int set) {
    CuteDogAssetMap file{};
    if (!cutedog_map_asset("samoyed-voice/A.cda", &file)) return false;
    struct Unmap { CuteDogAssetMap *file; ~Unmap() { cutedog_unmap_asset(file); } } unmap{&file};
    if (file.size <= 16 || file.size > max_state_bytes) return false;
    uint32_t version = 0, length = 0;
    uint64_t saved_key = 0;
    std::memcpy(&version, file.data, 4);
    std::memcpy(&length, file.data + 4, 4);
    std::memcpy(&saved_key, file.data + 8, 8);
    if (version != magic || saved_key != key || length == 0 || length > max_state_bytes) return false;
    std::vector<uint8_t> data(length);
    uLongf size = length;
    if (uncompress(data.data(), &size, file.data + 16, file.size - 16) != Z_OK || size != length) return false;
    if (!pipeline_codec_stream_reset(codec, set)) return false;
    const auto tensors = state_tensors(codec, set);
    size_t end = 0;
    for (auto *tensor : tensors) {
        if (size - end < 4) return false;
        uint32_t n = 0;
        std::memcpy(&n, data.data() + end, 4);
        end += 4;
        if (n != ggml_nbytes(tensor) || n > size - end) return false;
        end += n;
    }
    if (end != size) return false;
    size_t offset = 0;
    for (auto *tensor : tensors) {
        const size_t n = ggml_nbytes(tensor);
        ggml_backend_tensor_set(tensor, data.data() + offset + 4, 0, n);
        offset += 4 + n;
    }
    codec->stream_pos[set] = reference_frames;
    return pipeline_codec_stream_snapshot(codec, key, set);
}
#endif
