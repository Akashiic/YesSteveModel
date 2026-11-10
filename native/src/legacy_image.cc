// 3D Futura Engine: replaces upstream modules/legacy/src/v3/container/image.cc (YesSteveModel-Native, Apache-2.0).
// Upstream validates each historical image and then re-encodes it for its own container (WebP/ztx by role). Here the
// validation is the same (ValidateHistoricalImage, kept line for line) and the bytes stay as stored: RGBA or the
// original PNG/JPEG/WebP/AVIF file. The Java side turns RGBA into PNG; the exporter decodes WebP.
#include <v3/container/image.h>

#include <codec/image.h>
#include <err.h>

namespace ysm::legacy::v3::container {
namespace {
constexpr std::uint32_t kImageDimensionLimit = 4'096;

::ysm::codec::ImageFormat CodecFormat(ImageEncoding encoding) noexcept {
    switch (encoding) {
        case ImageEncoding::kRgba:
            return ::ysm::codec::ImageFormat::kRgba;
        case ImageEncoding::kPng:
            return ::ysm::codec::ImageFormat::kPng;
        case ImageEncoding::kJpeg:
            return ::ysm::codec::ImageFormat::kJpeg;
        case ImageEncoding::kWebp:
            return ::ysm::codec::ImageFormat::kWebp;
        case ImageEncoding::kAvif:
            return ::ysm::codec::ImageFormat::kAvif;
        case ImageEncoding::kZtx:
            return ::ysm::codec::ImageFormat::kZtx;
    }
    return ::ysm::codec::ImageFormat::kSize;
}

absl::Status ValidateHistoricalImage(const LegacyImage& image) {
    if (image.width == 0 || image.height == 0) {
        return absl::DataLossError("Historical image has an empty dimension");
    }
    if (image.width > kImageDimensionLimit || image.height > kImageDimensionLimit) {
        return absl::ResourceExhaustedError("Historical image dimension exceeds its limit");
    }
    if (image.frame_count != 1) {
        return absl::DataLossError("Historical image is not static");
    }
    const auto raw_size = static_cast<std::uint64_t>(image.width) * image.height * 4U;
    if (image.encoding == ImageEncoding::kRgba) {
        if (raw_size != image.bytes.size()) {
            return absl::DataLossError("Historical RGBA image length is inconsistent");
        }
        return absl::OkStatus();
    }
    const auto probed = ::ysm::codec::ImageProbe(BufferViewR(image.bytes));
    if (!probed.ok()) {
        return absl::DataLossError("Historical encoded image cannot be decoded");
    }
    if (probed->format != CodecFormat(image.encoding) || probed->width != image.width ||
        probed->height != image.height || probed->frame_count != 1) {
        return absl::DataLossError("Historical encoded image metadata is inconsistent");
    }
    return absl::OkStatus();
}
}  // namespace

absl::Status PrepareImage(LegacyImage& image, ImageRole role) {
    YSM_RETURN_IF_ERROR(ValidateHistoricalImage(image));
    image.role = role;
    return absl::OkStatus();
}

absl::Status ValidatePreparedImage(const LegacyImage& image, ImageRole role) {
    if (image.role != role) {
        return absl::FailedPreconditionError("Prepared image role differs");
    }
    return ValidateHistoricalImage(image);
}
}  // namespace ysm::legacy::v3::container
