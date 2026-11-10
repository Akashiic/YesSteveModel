// 3D Futura Engine: replaces upstream core/src/cpu.h (which needs cpu_features and magic_enum) with the subset the
// legacy v3 decoder uses: the SIMD dispatch type and the target attributes. Detection is in futura_shim.cc.
#pragma once

#include <cstdint>

#if defined(YSM_X64)
#include <emmintrin.h>
#if defined(__clang__) || defined(__GNUC__)
#define YSM_TARGET_AVX512 __attribute__((target("avx512f,avx512vl,avx512bw")))
#define YSM_TARGET_AVX2 __attribute__((target("avx2,fma")))
#define YSM_TARGET_SSE41 __attribute__((target("sse4.1,sse3,ssse3,popcnt")))
#else
#define YSM_TARGET_AVX512
#define YSM_TARGET_AVX2
#define YSM_TARGET_SSE41
#endif
#elif defined(YSM_ARM64)
#define YSM_TARGET_SVE
#else
#error "Unsupported cpu arch"
#endif

namespace ysm::simd {
enum class Type : uint16_t {
    kNone,
#ifdef YSM_X64
    SSE41,
    AVX2,
    AVX512,
#elif defined(YSM_ARM64)
    NEON,
#endif
};

extern Type kSupported;
}  // namespace ysm::simd
