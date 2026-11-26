#include <jni.h>
#include "cutedog_assets.h"
#include "qwen.h"
#include "ggml.h"
#include "sonic.h"
#include "rvq-file.h"
#include <algorithm>
#include <cmath>
#include <exception>
#include <string>
#include <vector>

namespace {
// The upstream model runs callbacks on an internal compute thread, while JNI render() blocks.
struct Sink {
    JavaVM *vm;
    jobject target;
    jmethodID stopped;
    jmethodID accept;
};

struct ScopedEnv {
    JavaVM *vm;
    JNIEnv *env = nullptr;
    bool attached = false;
    explicit ScopedEnv(JavaVM *machine) : vm(machine) {
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~ScopedEnv() { if (attached) vm->DetachCurrentThread(); }
};

bool cancelled(void *opaque) {
    const auto *sink = static_cast<Sink *>(opaque);
    ScopedEnv thread(sink->vm);
    if (!thread.env) return true;
    bool stopped = thread.env->CallBooleanMethod(sink->target, sink->stopped);
    if (thread.env->ExceptionCheck()) { thread.env->ExceptionClear(); return true; }
    return stopped;
}

bool samples(const float *input, int count, void *opaque) {
    auto *sink = static_cast<Sink *>(opaque);
    ScopedEnv thread(sink->vm);
    if (!thread.env || !input || count <= 0) return false;
    JNIEnv *env = thread.env;
    jshortArray data = env->NewShortArray(count);
    if (!data) { env->ExceptionClear(); return false; }
    auto *pcm = env->GetShortArrayElements(data, nullptr);
    if (!pcm) { env->ExceptionClear(); env->DeleteLocalRef(data); return false; }
    for (int i = 0; i < count; ++i)
        pcm[i] = static_cast<jshort>(std::lround(std::clamp(input[i], -1.0f, 1.0f) * 32767.f));
    env->ReleaseShortArrayElements(data, pcm, 0);
    const bool keep = env->CallBooleanMethod(sink->target, sink->accept, data);
    env->DeleteLocalRef(data);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return keep;
}

std::string utf8(JNIEnv *env, jbyteArray bytes) {
    const auto length = env->GetArrayLength(bytes);
    std::string text(length, '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(text.data()));
    return text;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_cutedog_app_voice_QwenNative_open(JNIEnv *env, jobject, jobject assets, jbyteArray talker, jbyteArray codec) {
    try {
        if (!cutedog_set_assets(env, assets)) return 0;
        auto t = utf8(env, talker), c = utf8(env, codec);
        if (env->ExceptionCheck()) return 0;
        qt_log_set([](qt_log_level, const char *, void *) {}, nullptr);
        ggml_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
        qt_init_params p{};
        qt_init_default_params(&p);
        p.talker_path = t.c_str();
        p.codec_path = c.c_str();
        p.device = "CPU";
        p.codec_chunk_sec = 8.0f;
        return reinterpret_cast<jlong>(qt_init(&p));
    } catch (const std::exception &) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cutedog_app_voice_QwenNative_render(JNIEnv *env, jobject, jlong handle, jbyteArray text,
                                           jbyteArray reference, jbyteArray embedding, jbyteArray codecReference,
                                           jint limit, jobject target) {
    try {
        const auto line = utf8(env, text), transcript = utf8(env, reference);
        if (!handle || env->ExceptionCheck()) return QT_STATUS_INVALID_PARAMS;
        // The signed APK contains an original reviewed A-voice encoded once by
        // the same Base model and 12Hz codec. All Android ABIs are little-endian.
        if (env->GetArrayLength(embedding) != 4096 || env->GetArrayLength(codecReference) != 1232)
            return QT_STATUS_INVALID_PARAMS;
        std::vector<float> speaker(1024);
        env->GetByteArrayRegion(embedding, 0, 4096, reinterpret_cast<jbyte *>(speaker.data()));
        std::vector<uint8_t> packed(1232);
        env->GetByteArrayRegion(codecReference, 0, 1232, reinterpret_cast<jbyte *>(packed.data()));
        std::vector<int32_t> codes;
        int frames = 0;
        auto *model = reinterpret_cast<qt_context *>(handle);
        if (env->ExceptionCheck() || !rvq_read_buf(packed.data(), packed.size(), qt_num_codebooks(model), 11, codes, &frames))
            return QT_STATUS_INVALID_PARAMS;
        jclass cls = env->GetObjectClass(target);
        Sink sink{};
        env->GetJavaVM(&sink.vm);
        sink.stopped = env->GetMethodID(cls, "stopped", "()Z");
        sink.accept = env->GetMethodID(cls, "accept", "([S)Z");
        env->DeleteLocalRef(cls);
        if (env->ExceptionCheck()) return QT_STATUS_INVALID_PARAMS;
        sink.target = env->NewGlobalRef(target);
        if (!sink.target) return QT_STATUS_OOM;
        qt_tts_params p{};
        qt_tts_default_params(&p);
        p.text = line.c_str();
        p.lang = "chinese";
        p.ref_text = transcript.c_str();
        p.ref_spk_emb = speaker.data();
        p.ref_spk_dim = static_cast<int>(speaker.size());
        p.ref_codes = codes.data();
        p.ref_T = frames;
        p.seed = 217;
        p.max_new_tokens = limit;
        p.temperature = .83f;
        p.top_p = .94f;
        p.top_k = 50;
        p.repetition_penalty = 1.05f;
        p.subtalker_temperature = .86f;
        p.cancel = cancelled;
        p.cancel_user_data = &sink;
        // A continuous clause is played as soon as it is ready. Decoding the clause
        // in one codec pass is considerably faster on mobile CPUs than per-frame decode.
        qt_audio out{};
        auto code = qt_synthesize(model, &p, &out);
        if (code == QT_STATUS_OK && !samples(out.samples, out.n_samples, &sink)) code = QT_STATUS_CANCELLED;
        qt_audio_free(&out);
        env->DeleteGlobalRef(sink.target);
        return code;
    } catch (const std::exception &) { return QT_STATUS_GENERATE_FAILED; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cutedog_app_voice_QwenNative_release(JNIEnv *, jobject, jlong handle) {
    if (handle) qt_free(reinterpret_cast<qt_context *>(handle));
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_cutedog_app_voice_QwenNative_tempo(JNIEnv *env, jobject, jshortArray input, jfloat speed) {
    sonicStream s = sonicCreateStream(24000, 1);
    if (!s) return nullptr;
    sonicSetSpeed(s, speed);
    const auto count = env->GetArrayLength(input);
    jshort *pcm = env->GetShortArrayElements(input, nullptr);
    if (!pcm) { sonicDestroyStream(s); return nullptr; }
    const bool ok = sonicWriteShortToStream(s, pcm, count) && sonicFlushStream(s);
    env->ReleaseShortArrayElements(input, pcm, JNI_ABORT);
    const auto n = ok ? sonicSamplesAvailable(s) : 0;
    auto out = n > 0 ? env->NewShortArray(n) : nullptr;
    if (out) {
        auto buffer = env->GetShortArrayElements(out, nullptr);
        if (buffer) {
            sonicReadShortFromStream(s, buffer, n);
            env->ReleaseShortArrayElements(out, buffer, 0);
        }
    }
    sonicDestroyStream(s);
    return out;
}
