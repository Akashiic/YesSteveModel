// 3D Futura Engine: turns a decoded legacy v3 model (upstream container::LegacyModel) into the files of an open-format
// model (ysm.json spec 2, Bedrock geometry/animation/controller JSON, images, sounds, functions, languages). This plays
// the role of upstream's conversion/projector.cc, which targets upstream's protobuf schema instead.
#pragma once

#include <absl/status/statusor.h>

#include <cstdint>
#include <string>
#include <vector>

#include <v3/container/model.h>

namespace futura::legacy {
enum class FileKind : std::uint8_t {
    // The bytes are the file.
    kBytes = 0,
    // The bytes are width*height RGBA pixels; the Java side encodes them as the PNG named by the path.
    kRgba = 1,
};

struct ExportedFile {
    std::string path;
    FileKind kind{FileKind::kBytes};
    std::uint32_t width{};
    std::uint32_t height{};
    std::string bytes;
};

struct ExportedModel {
    std::uint32_t inner_version{};
    std::vector<ExportedFile> files;
};

absl::StatusOr<ExportedModel> Export(ysm::legacy::v3::container::LegacyModel& model);

// The bytes handed to Java: "F3DL", format 1, inner version, file count, then per file: kind (u8), path (u16 length +
// UTF-8), width and height (u32, RGBA only), size (u32) and bytes. Big endian, as java.io.DataInputStream reads.
std::string Serialize(const ExportedModel& model);
}  // namespace futura::legacy
