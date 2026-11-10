// 3D Futura Engine: the subset of upstream codec/audio.h the legacy decoder needs. Upstream decodes with libopus and
// libvorbis; this shim only reads the Ogg headers (identification packet and the last granule position), which is
// all InspectAudio's callers use. Implemented in futura_shim.cc.
#pragma once

#include <absl/status/statusor.h>

#include <cstdint>

#include <buffer.h>

namespace ysm::codec {
enum class AudioCodec : std::uint8_t {
    kOpus,
    kVorbis,
};

struct AudioFacts {
    AudioCodec codec{AudioCodec::kOpus};
    std::uint8_t channels{};
    std::uint32_t sample_rate{};
    std::uint64_t frames{};
    std::uint64_t audio_packet_bytes{};
    std::uint32_t serial_number{};
    std::uint16_t pre_skip{};
    std::int16_t output_gain{};
};

[[nodiscard]] absl::StatusOr<AudioFacts> InspectAudio(BufferViewR source);
}  // namespace ysm::codec
