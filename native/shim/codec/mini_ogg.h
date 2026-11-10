// 3D Futura Engine: the one constant of upstream codec/mini_ogg.h that the legacy decoder reads.
#pragma once

#include <cstddef>

namespace ysm::codec {
inline constexpr std::size_t kOggPageHeaderSize = 27;
}  // namespace ysm::codec
