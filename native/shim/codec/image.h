// 3D Futura Engine: the subset of upstream codec/image.h the legacy decoder needs. ImageProbe reads only the headers
// (PNG, JPEG, WebP, AVIF, ztx1). Implemented in futura_shim.cc.
#pragma once

#include <absl/status/statusor.h>

#include <cstdint>

#include <buffer.h>

namespace ysm::codec {
enum class ImageFormat : uint32_t {
    kRgba = 0,
    kPng = 1,
    kJpeg = 2,
    kWebp = 3,
    kAvif = 4,
    kZtx = 5,

    kSize
};

struct ImageInfo {
    uint32_t width = 0;
    uint32_t height = 0;
    ImageFormat format = ImageFormat::kRgba;
    uint32_t frame_count = 0;
};

absl::StatusOr<ImageInfo> ImageProbe(BufferViewR image_buffer);
}  // namespace ysm::codec
