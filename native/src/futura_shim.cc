// 3D Futura Engine: header-only replacements for the parts of upstream ysm-core and ysm-codec that the legacy v3
// decoder calls (SIMD detection, ImageProbe, InspectAudio). Upstream links cpu_features, libspng, libjpeg-turbo,
// libwebp, libavif, libopus and libvorbis for these; the decoder only needs the facts in the headers.
#include <codec/audio.h>
#include <codec/image.h>
#include <codec/mini_ogg.h>
#include <cpu.h>

#include <cstring>

namespace ysm::simd {
namespace {
Type Detect() noexcept {
#if defined(YSM_X64) && (defined(__GNUC__) || defined(__clang__))
    __builtin_cpu_init();
    if (__builtin_cpu_supports("avx2")) {
        return Type::AVX2;
    }
    if (__builtin_cpu_supports("sse4.1")) {
        return Type::SSE41;
    }
    return Type::kNone;
#elif defined(YSM_ARM64)
    return Type::NEON;
#else
    return Type::kNone;
#endif
}
}  // namespace

Type kSupported = Detect();
}  // namespace ysm::simd

namespace ysm::codec {
namespace {
std::uint32_t Be32(const Byte* p) {
    return (std::uint32_t(p[0]) << 24) | (std::uint32_t(p[1]) << 16) | (std::uint32_t(p[2]) << 8) | p[3];
}
std::uint32_t Le32(const Byte* p) {
    return std::uint32_t(p[0]) | (std::uint32_t(p[1]) << 8) | (std::uint32_t(p[2]) << 16) |
           (std::uint32_t(p[3]) << 24);
}
std::uint64_t Le64(const Byte* p) {
    return std::uint64_t(Le32(p)) | (std::uint64_t(Le32(p + 4)) << 32);
}
std::uint32_t Le24(const Byte* p) {
    return std::uint32_t(p[0]) | (std::uint32_t(p[1]) << 8) | (std::uint32_t(p[2]) << 16);
}
bool Starts(BufferViewR b, const char* magic, std::size_t n, std::size_t at = 0) {
    return b.size() >= at + n && std::memcmp(b.data() + at, magic, n) == 0;
}

absl::StatusOr<ImageInfo> ProbePng(BufferViewR b) {
    // signature, IHDR length, "IHDR", width, height
    if (b.size() < 24 || !Starts(b, "IHDR", 4, 12)) {
        return absl::InvalidArgumentError("Truncated PNG header");
    }
    // An APNG (acTL before IDAT) is animated: frame count from acTL.
    std::uint32_t frames = 1;
    std::size_t at = 8;
    while (at + 8 <= b.size()) {
        const auto length = Be32(b.data() + at);
        if (Starts(b, "acTL", 4, at + 4) && at + 12 <= b.size()) {
            frames = Be32(b.data() + at + 8);
            break;
        }
        if (Starts(b, "IDAT", 4, at + 4) || length > b.size()) {
            break;
        }
        at += 12 + std::size_t(length);
    }
    return ImageInfo{Be32(b.data() + 16), Be32(b.data() + 20), ImageFormat::kPng, frames};
}

absl::StatusOr<ImageInfo> ProbeJpeg(BufferViewR b) {
    std::size_t at = 2;
    while (at + 4 <= b.size()) {
        if (b[at] != 0xFF) {
            return absl::InvalidArgumentError("Bad JPEG marker");
        }
        const Byte marker = b[at + 1];
        if (marker == 0xFF) {
            ++at;
            continue;
        }
        if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
            at += 2;
            continue;
        }
        const std::size_t length = (std::size_t(b[at + 2]) << 8) | b[at + 3];
        const bool sof = marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if (sof) {
            if (at + 9 > b.size()) {
                break;
            }
            const std::uint32_t height = (std::uint32_t(b[at + 5]) << 8) | b[at + 6];
            const std::uint32_t width = (std::uint32_t(b[at + 7]) << 8) | b[at + 8];
            return ImageInfo{width, height, ImageFormat::kJpeg, 1};
        }
        at += 2 + length;
    }
    return absl::InvalidArgumentError("JPEG has no frame header");
}

absl::StatusOr<ImageInfo> ProbeWebp(BufferViewR b) {
    if (b.size() < 30) {
        return absl::InvalidArgumentError("Truncated WebP header");
    }
    const Byte* chunk = b.data() + 12;
    if (std::memcmp(chunk, "VP8X", 4) == 0) {
        const bool animated = (chunk[8] & 0x02) != 0;
        return ImageInfo{Le24(chunk + 12) + 1, Le24(chunk + 15) + 1, ImageFormat::kWebp, animated ? 2U : 1U};
    }
    if (std::memcmp(chunk, "VP8L", 4) == 0) {
        const std::uint32_t bits = Le32(chunk + 9);
        return ImageInfo{(bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1, ImageFormat::kWebp, 1};
    }
    if (std::memcmp(chunk, "VP8 ", 4) == 0) {
        const Byte* frame = chunk + 8;
        if (frame[3] != 0x9D || frame[4] != 0x01 || frame[5] != 0x2A) {
            return absl::InvalidArgumentError("Bad VP8 start code");
        }
        const std::uint32_t width = (std::uint32_t(frame[6]) | (std::uint32_t(frame[7]) << 8)) & 0x3FFF;
        const std::uint32_t height = (std::uint32_t(frame[8]) | (std::uint32_t(frame[9]) << 8)) & 0x3FFF;
        return ImageInfo{width, height, ImageFormat::kWebp, 1};
    }
    return absl::InvalidArgumentError("Unknown WebP chunk");
}

// AVIF: the first "ispe" property box gives the size; an "avis" brand means an image sequence.
absl::StatusOr<ImageInfo> ProbeAvif(BufferViewR b) {
    const bool sequence = b.size() >= 12 && Starts(b, "avis", 4, 8);
    for (std::size_t at = 0; at + 20 <= b.size(); ++at) {
        if (std::memcmp(b.data() + at, "ispe", 4) == 0) {
            return ImageInfo{Be32(b.data() + at + 8), Be32(b.data() + at + 12), ImageFormat::kAvif,
                             sequence ? 2U : 1U};
        }
    }
    return absl::InvalidArgumentError("AVIF has no ispe box");
}

struct OggPage {
    std::uint64_t granule;
    std::uint32_t serial;
    std::size_t body;
    std::size_t body_size;
    std::size_t next;
};

bool ReadOggPage(BufferViewR b, std::size_t at, OggPage& page) {
    if (at + kOggPageHeaderSize > b.size() || std::memcmp(b.data() + at, "OggS", 4) != 0) {
        return false;
    }
    const std::size_t segments = b[at + 26];
    if (at + kOggPageHeaderSize + segments > b.size()) {
        return false;
    }
    std::size_t size = 0;
    for (std::size_t i = 0; i < segments; ++i) {
        size += b[at + kOggPageHeaderSize + i];
    }
    page.granule = Le64(b.data() + at + 6);
    page.serial = Le32(b.data() + at + 14);
    page.body = at + kOggPageHeaderSize + segments;
    page.body_size = size;
    page.next = page.body + size;
    return page.next <= b.size();
}
}  // namespace

absl::StatusOr<ImageInfo> ImageProbe(BufferViewR b) {
    static constexpr Byte kPng[8] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    if (b.size() >= 8 && std::memcmp(b.data(), kPng, 8) == 0) {
        return ProbePng(b);
    }
    if (b.size() >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) {
        return ProbeJpeg(b);
    }
    if (Starts(b, "RIFF", 4) && Starts(b, "WEBP", 4, 8)) {
        return ProbeWebp(b);
    }
    if (Starts(b, "ftyp", 4, 4) && (Starts(b, "avif", 4, 8) || Starts(b, "avis", 4, 8))) {
        return ProbeAvif(b);
    }
    if (Starts(b, "ztx1", 4) && b.size() > 12) {
        return ImageInfo{Le32(b.data() + 4), Le32(b.data() + 8), ImageFormat::kZtx, 1};
    }
    return absl::InvalidArgumentError("Unsupported image format.");
}

absl::StatusOr<AudioFacts> InspectAudio(BufferViewR b) {
    OggPage first{};
    if (!ReadOggPage(b, 0, first)) {
        return absl::InvalidArgumentError("Not an Ogg stream");
    }
    AudioFacts facts{};
    facts.serial_number = first.serial;
    const Byte* packet = b.data() + first.body;
    if (first.body_size >= 19 && std::memcmp(packet, "OpusHead", 8) == 0) {
        facts.codec = AudioCodec::kOpus;
        facts.channels = packet[9];
        facts.pre_skip = std::uint16_t(packet[10] | (packet[11] << 8));
        facts.sample_rate = 48000;  // Opus always decodes at 48 kHz; the header's rate is informational
        facts.output_gain = std::int16_t(packet[16] | (packet[17] << 8));
    } else if (first.body_size >= 30 && packet[0] == 1 && std::memcmp(packet + 1, "vorbis", 6) == 0) {
        facts.codec = AudioCodec::kVorbis;
        facts.channels = packet[11];
        facts.sample_rate = Le32(packet + 12);
    } else {
        return absl::InvalidArgumentError("Neither Opus nor Vorbis");
    }
    if (facts.channels == 0 || facts.sample_rate == 0) {
        return absl::InvalidArgumentError("Empty audio parameters");
    }
    // Frames: the granule position of the last page of the logical stream (minus Opus pre-skip).
    std::uint64_t last = 0;
    std::size_t at = 0;
    OggPage page{};
    while (ReadOggPage(b, at, page)) {
        if (page.serial == facts.serial_number && page.granule != ~std::uint64_t(0)) {
            last = page.granule;
        }
        if (at != 0) {
            facts.audio_packet_bytes += page.body_size;
        }
        at = page.next;
    }
    if (at != b.size()) {
        return absl::DataLossError("Truncated Ogg page");
    }
    facts.frames = facts.codec == AudioCodec::kOpus && last > facts.pre_skip ? last - facts.pre_skip : last;
    if (facts.frames == 0) {
        return absl::DataLossError("Ogg stream has no audio");
    }
    return facts;
}
}  // namespace ysm::codec
