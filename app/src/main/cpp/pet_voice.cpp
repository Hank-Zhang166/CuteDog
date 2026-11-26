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

bool cutedog_prime_voice(qt_context *, const int32_t *, int);
void cutedog_bind_cpu_pool(qt_context *);

namespace {
// The upstream model runs callbacks on an internal compute thread, while JNI render() blocks.
struct Sink {
    JavaVM *vm;
    jobject target;
    jmethodID stopped;
    jmethodID accept;
    sonicStream tempo{};
    bool voiced = false;
    std::vector<short> quiet;
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

JNIEnv *model_env(JavaVM *vm) {
    // qwentts owns one long-lived compute worker. Repeatedly attaching that
    // thread to ART for cancellation and detaching it at every frame disrupts
    // generation. Its thread-local destructor detaches when qt_free joins it.
    thread_local ScopedEnv attached(vm);
    return attached.env;
}

bool cancelled(void *opaque) {
    const auto *sink = static_cast<Sink *>(opaque);
    auto *env = model_env(sink->vm);
    if (!env) return true;
    bool stopped = env->CallBooleanMethod(sink->target, sink->stopped);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return true; }
    return stopped;
}

bool send_pcm(Sink *sink) {
    const int count = sonicSamplesAvailable(sink->tempo);
    if (count <= 0) return true;
    JNIEnv *env = model_env(sink->vm);
    if (!env) return false;
    jshortArray data = env->NewShortArray(count);
    if (!data) { env->ExceptionClear(); return false; }
    auto *pcm = env->GetShortArrayElements(data, nullptr);
    if (!pcm) { env->ExceptionClear(); env->DeleteLocalRef(data); return false; }
    sonicReadShortFromStream(sink->tempo, pcm, count);
    env->ReleaseShortArrayElements(data, pcm, 0);
    const bool keep = env->CallBooleanMethod(sink->target, sink->accept, data);
    env->DeleteLocalRef(data);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return keep;
}

// All chunks use one pitch-preserving tempo stream. Hold only silent 10ms windows
// so a chunk boundary can never cut a syllable or restart an envelope.
bool samples(const float *input, int count, void *opaque) {
    auto *s = static_cast<Sink *>(opaque);
    std::vector<short> out;
    out.reserve(count + s->quiet.size());
    for (int i = 0; i < count; i += 240) {
        const int stop = std::min(i + 240, count);
        double energy = 0;
        for (int j = i; j < stop; ++j) energy += input[j] * input[j];
        const float cutoff = s->voiced ? .0038f : .0010f;
        const bool audible = energy > (stop - i) * cutoff * cutoff;
        if (audible) {
            out.insert(out.end(), s->quiet.begin(), s->quiet.end());
            s->quiet.clear();
            s->voiced = true;
        }
        if (!audible && s->quiet.size() >= (s->voiced ? 8640u : 960u)) continue;
        auto &write = audible ? out : s->quiet;
        for (int j = i; j < stop; ++j) {
            const float x = 1.12f * input[j], a = std::abs(x);
            const float peak = a > .94f ? std::copysign(.94f + .06f * (a - .94f) / (a - .88f), x) : x;
            write.push_back(static_cast<short>(std::lround(peak * 32767.f)));
        }
    }
    return (out.empty() || sonicWriteShortToStream(s->tempo, out.data(), out.size())) && send_pcm(s);
}

std::string utf8(JNIEnv *env, jbyteArray bytes) {
    const auto length = env->GetArrayLength(bytes);
    std::string text(length, '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(text.data()));
    return text;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_cutedog_app_voice_QwenNative_prime(JNIEnv *env, jobject, jlong engine, jbyteArray reference) {
    if (!engine || env->GetArrayLength(reference) != 1232) return false;
    try {
        std::vector<uint8_t> packed(1232);
        env->GetByteArrayRegion(reference, 0, 1232, reinterpret_cast<jbyte *>(packed.data()));
        int frames = 0;
        std::vector<int32_t> codes;
        auto *model = reinterpret_cast<qt_context *>(engine);
        return !env->ExceptionCheck() && rvq_read_buf(packed.data(), packed.size(), qt_num_codebooks(model), 11, codes, &frames)
            && cutedog_prime_voice(model, codes.data(), frames);
    } catch (const std::exception &) { return false; }
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
        auto *context = qt_init(&p);
        cutedog_bind_cpu_pool(context);
        return reinterpret_cast<jlong>(context);
    } catch (const std::exception &) { return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_cutedog_app_voice_QwenNative_render(JNIEnv *env, jobject, jlong handle, jbyteArray text,
                                           jbyteArray reference, jbyteArray embedding, jbyteArray codecReference,
                                           jint limit, jfloat speed, jobject target) {
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
        sink.tempo = sonicCreateStream(24000, 1);
        if (!sink.tempo) { env->DeleteGlobalRef(sink.target); return QT_STATUS_OOM; }
        sonicSetSpeed(sink.tempo, speed);
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
        p.on_chunk = samples;
        p.on_chunk_user_data = &sink;
        qt_audio out{};
        auto code = qt_synthesize(model, &p, &out);
        if (code == QT_STATUS_OK) {
            const int tail = std::min<int>(sink.quiet.size(), 1920);
            if (tail) sonicWriteShortToStream(sink.tempo, sink.quiet.data(), tail);
            if (!sonicFlushStream(sink.tempo) || !send_pcm(&sink)) code = QT_STATUS_CANCELLED;
        }
        sonicDestroyStream(sink.tempo);
        qt_audio_free(&out);
        env->DeleteGlobalRef(sink.target);
        return code;
    } catch (const std::exception &) { return QT_STATUS_GENERATE_FAILED; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_cutedog_app_voice_QwenNative_release(JNIEnv *, jobject, jlong handle) {
    if (handle) qt_free(reinterpret_cast<qt_context *>(handle));
}
