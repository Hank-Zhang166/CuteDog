#include <jni.h>
#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#elif defined(__x86_64__)
#include <cpuid.h>
#endif

// Compiled for the base ABI. Never enter an ISA-specific library before checking
// OS feature bits (including enabled vector register state on x86).
extern "C" JNIEXPORT jint JNICALL
Java_com_cutedog_app_voice_QwenNative_cpuLevel(JNIEnv *, jobject) {
#if defined(__aarch64__)
    const auto required = HWCAP_ASIMD | HWCAP_FPHP | HWCAP_ASIMDHP | HWCAP_ASIMDDP | HWCAP_ATOMICS;
    if ((getauxval(AT_HWCAP) & required) != required) return 0;
    return getauxval(AT_HWCAP2) & HWCAP2_I8MM ? 2 : 1;
#elif defined(__x86_64__)
    unsigned a,b,c,d;
    if (!__get_cpuid(1,&a,&b,&c,&d)) return 0;
    const unsigned needed = bit_SSE4_2 | bit_AVX | bit_OSXSAVE | bit_FMA | bit_F16C;
    if ((c & needed) != needed) return 0;
    unsigned lo, hi;
    __asm__ volatile ("xgetbv" : "=a"(lo), "=d"(hi) : "c"(0));
    if ((lo & 6) != 6 || !__get_cpuid_count(7,0,&a,&b,&c,&d)) return 0;
    return (b & (bit_AVX2 | bit_BMI2)) == (bit_AVX2 | bit_BMI2) ? 1 : 0;
#else
    return 0;
#endif
}
