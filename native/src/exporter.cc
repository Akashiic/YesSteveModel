// 3D Futura Engine: legacy v3 model -> open-format files. Field meanings follow upstream's
// modules/legacy/src/v3/conversion/projector.cc (YesSteveModel-Native, Apache-2.0); the output is the open authoring
// format that the 3D Futura Engine loader already reads, so a legacy model goes through the same loader, limits and
// network transfer as any other model.
//
// Geometry: a legacy model stores baked quads (cube rotation applied, blocks, x negated: upstream's cubes_legacy, the
// same numbers the Java GeoBaker produces). They are written as a Bedrock "poly_mesh" per bone, in Bedrock units
// (pixels, x not negated), with normalized UVs measured from the top as the cube UVs are, plus
// "futura3d_cube_faces" (quads per cube, in order) so that the loader can regroup the quads into cubes for culling.
#include "exporter.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <numbers>
#include <set>
#include <string_view>

#include "json_writer.h"

#ifdef FUTURA_WITH_WEBP
#include <webp/decode.h>
#endif

namespace futura::legacy {
namespace c = ysm::legacy::v3::container;
using json::Writer;

namespace {
// upstream com.elfmcys.ysm.format.schema.model.ModelOriginVersion.RELEASES; a legacy file stores the index.
constexpr std::string_view kReleases[] = {
    "0.0.0",
    "1.2.0",
    "2.2.1-snapshot",
    "2.2.1-snapshot-3",
    "2.2.1",
    "2.2.2-snapshot-3",
    "2.2.2-snapshot-11",
    "2.2.2-snapshot-14",
    "2.2.2-snapshot-19",
    "2.2.2",
    "2.2.3-snapshot",
    "2.2.3-snapshot-6",
    "2.2.3-snapshot-8",
    "2.2.3-snapshot-11",
    "2.2.3-snapshot-28",
    "2.3.0",
    "2.4.2-snapshot-1",
    "2.4.2-snapshot-5",
    "2.4.2-snapshot-21",
    "2.4.2-snapshot-32",
    "2.4.2-snapshot-35",
    "2.4.2-snapshot-37",
    "2.4.2-snapshot-39",
    "2.4.2-snapshot-40",
    "2.4.2-snapshot-41",
    "2.4.2-snapshot-45",
    "2.5.0",
    "2.5.2-snapshot-2",
    "2.5.2",
    "2.5.4-snapshot-2",
    "2.5.4-snapshot-3",
    "2.6.0",
    "2.7.0",
    "3.0.0",
};

bool UnsignedLess(std::string_view a, std::string_view b) {
    return std::lexicographical_compare(a.begin(), a.end(), b.begin(), b.end(), [](char x, char y) {
        return static_cast<unsigned char>(x) < static_cast<unsigned char>(y);
    });
}

template <typename Value>
std::vector<const std::pair<std::string, Value>*> Sorted(const c::OrderedMap<std::string, Value>& map) {
    std::vector<const std::pair<std::string, Value>*> out;
    for (const auto& entry : map) {
        out.push_back(&entry);
    }
    std::stable_sort(out.begin(), out.end(),
                     [](const auto* a, const auto* b) { return UnsignedLess(a->first, b->first); });
    return out;
}

std::string Bytes(const ysm::BufferManaged& buffer) {
    return std::string(reinterpret_cast<const char*>(buffer.data()), buffer.size());
}

double TicksToSeconds(double ticks) { return ticks / 20.0; }

class Exporter {
   public:
    explicit Exporter(c::LegacyModel& model) : model_(model) {}

    absl::StatusOr<ExportedModel> Run() {
        ExportedModel result;
        result.inner_version = model_.version;
        Writer manifest;
        manifest.BeginObject();
        manifest.Key("spec").Int(2);
        if (model_.info.metadata) {
            auto status = WriteMetadata(manifest, *model_.info.metadata);
            if (!status.ok()) {
                return status;
            }
        }
        WriteProperties(manifest);
        manifest.Key("files").BeginObject();
        if (model_.player) {
            auto status = WritePlayer(manifest, *model_.player);
            if (!status.ok()) {
                return status;
            }
        }
        for (const char* group : {"projectiles", "vehicles"}) {
            auto& list = std::string_view(group) == "projectiles" ? model_.projectiles : model_.vehicles;
            if (list.empty()) {
                continue;
            }
            std::vector<c::ReplaceModel*> ordered;
            for (auto& value : list) {
                ordered.push_back(&value);
            }
            if (model_.version <= 26) {
                std::stable_sort(ordered.begin(), ordered.end(), [](const auto* a, const auto* b) {
                    return UnsignedLess(a->source_key, b->source_key);
                });
            }
            manifest.Key(group).BeginArray();
            for (std::size_t index = 0; index < ordered.size(); ++index) {
                auto status = WriteReplacement(manifest, *ordered[index], group, index);
                if (!status.ok()) {
                    return status;
                }
            }
            manifest.EndArray();
        }
        manifest.Field("sound_path", "sounds");
        manifest.Field("function_path", "functions");
        manifest.Field("language_path", "lang");
        manifest.EndObject();
        manifest.EndObject();
        WriteCommon();
        Add("ysm.json", manifest.Take());
        if (!status_.ok()) {
            return status_;
        }
        result.files = std::move(files_);
        return result;
    }

   private:
    c::LegacyModel& model_;
    std::vector<ExportedFile> files_;
    std::set<std::string> used_;
    absl::Status status_ = absl::OkStatus();

    // A name from the model as a relative path under `directory`: kept as written where it can be (sounds and
    // functions are called by name), with anything a path cannot hold replaced by '_'.
    static std::string SafeName(std::string_view name) {
        std::string out;
        std::string segment;
        auto flush = [&] {
            if (segment.empty()) {
                return;
            }
            if (segment == "." || segment == "..") {
                segment = "_";
            }
            if (!out.empty()) {
                out += '/';
            }
            out += segment;
            segment.clear();
        };
        for (const char ch : name) {
            const auto u = static_cast<unsigned char>(ch);
            if (ch == '/') {
                flush();
            } else if (u < 0x20 || ch == '\\' || ch == ':' || ch == '*' || ch == '?' || ch == '"' || ch == '<' ||
                       ch == '>' || ch == '|') {
                segment += '_';
            } else {
                segment += ch;
            }
        }
        flush();
        return out.empty() ? "_" : out;
    }

    std::string Unique(std::string stem, std::string_view extension) {
        std::string path = stem + std::string(extension);
        for (int n = 2; used_.count(path) != 0; ++n) {
            path = stem + "_" + std::to_string(n) + std::string(extension);
        }
        used_.insert(path);
        return path;
    }

    void Add(std::string path, std::string bytes) {
        used_.insert(path);
        files_.push_back(ExportedFile{std::move(path), FileKind::kBytes, 0, 0, std::move(bytes)});
    }

    // Stores an image under `stem` and returns its path, or "" (with status_ set) if it cannot be represented.
    std::string AddImage(const std::string& stem, const c::LegacyImage& image) {
        switch (image.encoding) {
            case c::ImageEncoding::kRgba: {
                auto path = Unique(stem, ".png");
                files_.push_back(ExportedFile{path, FileKind::kRgba, image.width, image.height, Bytes(image.bytes)});
                return path;
            }
            case c::ImageEncoding::kPng: {
                auto path = Unique(stem, ".png");
                files_.push_back(ExportedFile{path, FileKind::kBytes, 0, 0, Bytes(image.bytes)});
                return path;
            }
            case c::ImageEncoding::kJpeg: {
                auto path = Unique(stem, ".jpg");
                files_.push_back(ExportedFile{path, FileKind::kBytes, 0, 0, Bytes(image.bytes)});
                return path;
            }
            case c::ImageEncoding::kWebp: {
#ifdef FUTURA_WITH_WEBP
                int width = 0;
                int height = 0;
                std::uint8_t* pixels =
                    WebPDecodeRGBA(image.bytes.data(), image.bytes.size(), &width, &height);
                if (pixels == nullptr || std::uint32_t(width) != image.width ||
                    std::uint32_t(height) != image.height) {
                    WebPFree(pixels);
                    status_ = absl::DataLossError("A WebP image of the model cannot be decoded");
                    return "";
                }
                auto path = Unique(stem, ".png");
                files_.push_back(ExportedFile{path, FileKind::kRgba, image.width, image.height,
                                              std::string(reinterpret_cast<const char*>(pixels),
                                                          std::size_t(width) * height * 4)});
                WebPFree(pixels);
                return path;
#else
                status_ = absl::UnimplementedError("WebP images are not supported by this build");
                return "";
#endif
            }
            case c::ImageEncoding::kAvif:
                status_ = absl::UnimplementedError("AVIF images are not supported yet");
                return "";
            case c::ImageEncoding::kZtx:
                break;
        }
        status_ = absl::DataLossError("Unknown image encoding");
        return "";
    }

    absl::Status WriteMetadata(Writer& w, const c::ModelMetadata& metadata) {
        w.Key("metadata").BeginObject();
        w.Field("name", metadata.name);
        if (!metadata.tips.empty()) {
            w.Field("tips", metadata.tips);
        }
        w.Key("license").BeginObject();
        w.Field("type", metadata.license.type);
        if (!metadata.license.description.empty()) {
            w.Field("desc", metadata.license.description);
        }
        w.EndObject();
        w.Key("authors").BeginArray();
        for (const auto& author : metadata.authors) {
            w.BeginObject();
            w.Field("name", author.name);
            if (!author.role.empty()) {
                w.Field("role", author.role);
            }
            if (!author.comment.empty()) {
                w.Field("comment", author.comment);
            }
            if (!author.contact.empty()) {
                w.Key("contact").BeginObject();
                for (const auto& [key, value] : author.contact) {
                    w.Field(key, value);
                }
                w.EndObject();
            }
            const auto avatar = std::find_if(model_.info.author_avatars.begin(), model_.info.author_avatars.end(),
                                             [&](const auto& entry) { return entry.first == author.name; });
            if (avatar != model_.info.author_avatars.end()) {
                // an avatar that cannot be represented is left out, not the model
                auto path = AddImage("avatar/" + SafeName(author.name), avatar->second);
                if (path.empty()) {
                    status_ = absl::OkStatus();
                } else {
                    w.Field("avatar", path);
                }
            }
            w.EndObject();
        }
        w.EndArray();
        if (!metadata.links.empty()) {
            w.Key("link").BeginObject();
            for (const auto* entry : Sorted(metadata.links)) {
                w.Field(entry->first, entry->second);
            }
            w.EndObject();
        }
        w.EndObject();
        return absl::OkStatus();
    }

    void WriteProperties(Writer& w) {
        const auto& p = model_.info.properties;
        w.Key("properties").BeginObject();
        w.FieldFloat("height_scale", p.height_scale);
        w.FieldFloat("width_scale", p.width_scale);
        w.Key("extra_animation").BeginObject();
        if (p.extra_animation.empty()) {
            // projector.cc ProjectInfoSettings: no extra animations means the eight default slots
            for (int index = 0; index < 8; ++index) {
                w.Field("extra" + std::to_string(index), "");
            }
        } else {
            for (const auto* entry : Sorted(p.extra_animation)) {
                w.Field(entry->first, entry->second);
            }
        }
        w.EndObject();
        w.Key("extra_animation_buttons").BeginArray();
        for (const auto& button : p.extra_animation_buttons) {
            w.BeginObject();
            w.Field("id", button.id);
            w.Field("name", button.name);
            if (!button.sound.empty()) {
                w.Field("sound", button.sound);
            }
            w.Key("config_forms").BeginArray();
            for (const auto& form : button.forms) {
                w.BeginObject();
                w.Field("type", form.type);
                w.Field("title", form.title);
                w.Field("description", form.description);
                w.Field("value", form.value);
                w.FieldDouble("step", form.step);
                w.FieldDouble("min", form.minimum);
                w.FieldDouble("max", form.maximum);
                if (!form.labels.empty()) {
                    w.Key("labels").BeginObject();
                    for (const auto* label : Sorted(form.labels)) {
                        w.Field(label->first, label->second);
                    }
                    w.EndObject();
                }
                w.EndObject();
            }
            w.EndArray();
            w.EndObject();
        }
        w.EndArray();
        w.Key("extra_animation_classify").BeginArray();
        for (const auto& classify : p.extra_animation_classify) {
            w.BeginObject();
            w.Field("id", classify.id);
            w.Key("extra_animation").BeginObject();
            for (const auto* entry : Sorted(classify.animations)) {
                w.Field(entry->first, entry->second);
            }
            w.EndObject();
            w.EndObject();
        }
        w.EndArray();
        if (!p.default_texture.empty()) {
            w.Field("default_texture", p.default_texture);
        }
        if (!p.preview_animation.empty()) {
            w.Field("preview_animation", p.preview_animation);
        }
        w.Field("render_layers_first", p.render_layers_first);
        w.Field("free", p.free);
        w.Field("all_cutout", p.all_cutout);
        w.Field("disable_preview_rotation", p.disable_preview_rotation);
        w.Field("gui_no_lighting", p.gui_no_lighting);
        w.Field("merge_multiline_expr", p.merge_multiline_expr);
        const auto origin = model_.info.origin_version;
        // upstream ModelProperties.originVer has no @SerializedName: Gson reads the key "originVer"
        w.Field("originVer",
                origin < std::size(kReleases) ? kReleases[origin] : kReleases[std::size(kReleases) - 1]);
        for (const auto& [key, image] : model_.info.gui_images) {
            auto path = AddImage("gui/" + SafeName(key), image);
            if (path.empty()) {
                status_ = absl::OkStatus();  // optional art: left out
                continue;
            }
            if (key == "gui_foreground" || key == "gui_background") {
                w.Field(key, path);
            } else if (key == "thumb-button") {
                w.Field("thumbnail", path);
            } else if (key == "thumb-icon") {
                w.Field("icon", path);
            }
        }
        w.EndObject();
    }

    std::string WriteTextureSet(const std::string& stem, const c::TextureSet& set, Writer& w) {
        auto uv = AddImage(stem, set.uv.value);
        if (uv.empty()) {
            return "";
        }
        w.BeginObject();
        w.Field("uv", uv);
        for (const auto& [type, image] : set.pbr) {
            const char* suffix = type == c::PbrType::kNormal ? "_n" : "_s";
            auto path = AddImage(stem + suffix, image.value);
            if (!path.empty()) {
                w.Field(type == c::PbrType::kNormal ? "normal" : "specular", path);
            }
        }
        w.EndObject();
        return uv;
    }

    static const char* AnimationSetName(c::PlayerAnimationType type) {
        switch (type) {
            case c::PlayerAnimationType::kMain:
                return "main";
            case c::PlayerAnimationType::kArm:
                return "arm";
            case c::PlayerAnimationType::kExtra:
                return "extra";
            case c::PlayerAnimationType::kTac:
                return "tac";
            case c::PlayerAnimationType::kCarryOn:
                return "carryon";
            case c::PlayerAnimationType::kParCool:
                return "parcool";
            case c::PlayerAnimationType::kSwem:
                return "swem";
            case c::PlayerAnimationType::kSlashBlade:
                return "slashblade";
            case c::PlayerAnimationType::kTlm:
                return "tlm";
            case c::PlayerAnimationType::kFirstPersonArm:
                return "fp_arm";
            case c::PlayerAnimationType::kImmersiveMelodies:
                return "immersive_melodies";
            case c::PlayerAnimationType::kIronsSpellBooks:
                return "irons_spell_books";
        }
        return nullptr;
    }

    absl::Status WritePlayer(Writer& w, const c::PlayerModel& player) {
        if (player.textures.empty()) {
            return absl::FailedPreconditionError("Player target has no texture");
        }
        w.Key("player").BeginObject();
        w.Key("model").BeginObject();
        for (const auto& [type, geo] : player.geo_models) {
            const char* name = type == c::PlayerModelType::kMain ? "main" : type == c::PlayerModelType::kArm ? "arm"
                                                                                                             : nullptr;
            if (name == nullptr) {
                return absl::FailedPreconditionError("Unknown player geometry");
            }
            auto path = Unique(std::string("models/") + name, ".json");
            Add(path, Geometry(geo.value));
            w.Field(name, path);
        }
        w.EndObject();
        w.Key("animation").BeginObject();
        for (const auto& [type, file] : player.animations) {
            const char* name = AnimationSetName(type);
            if (name == nullptr) {
                return absl::FailedPreconditionError("Unknown player animation set");
            }
            auto path = Unique(std::string("animations/") + name, ".animation.json");
            Add(path, Animations(file.value));
            w.Field(name, path);
        }
        w.EndObject();
        w.Key("animation_controllers").BeginArray();
        for (const auto* entry : Sorted(player.controllers)) {
            auto path = Unique("controller/" + SafeName(entry->first), ".json");
            Add(path, Controllers(entry->second.value));
            w.String(path);
        }
        w.EndArray();
        w.Key("texture").BeginArray();
        for (const auto* entry : Sorted(player.textures)) {
            if (WriteTextureSet("textures/" + SafeName(entry->first), entry->second, w).empty()) {
                return status_;
            }
        }
        w.EndArray();
        w.EndObject();
        return absl::OkStatus();
    }

    absl::Status WriteReplacement(Writer& w, const c::ReplaceModel& source, std::string_view group,
                                  std::size_t index) {
        const std::string stem = std::string(group) + "/" + std::to_string(index) + "/";
        w.BeginObject();
        w.Key("match").BeginArray();
        for (const auto& match : source.match) {
            w.String(match);
        }
        w.EndArray();
        if (source.geo) {
            auto path = Unique(stem + "model", ".json");
            Add(path, Geometry(source.geo->value));
            w.Field("model", path);
        }
        if (source.animation) {
            auto path = Unique(stem + "main", ".animation.json");
            Add(path, Animations(source.animation->value));
            w.Field("animation", path);
        }
        if (source.controller) {
            auto path = Unique(stem + "controller", ".json");
            Add(path, Controllers(source.controller->value));
            w.Field("controller", path);
        }
        if (source.texture) {
            w.Key("texture");
            if (WriteTextureSet(stem + "texture", *source.texture, w).empty()) {
                return status_;
            }
        }
        w.EndObject();
        return absl::OkStatus();
    }

    void WriteCommon() {
        for (const auto& [name, sound] : model_.common.sounds) {
            Add(Unique("sounds/" + SafeName(name), ".ogg"), Bytes(sound.value.bytes));
        }
        for (const auto& [name, function] : model_.common.user_functions) {
            Add(Unique("functions/" + SafeName(name), ".molang"), function.value);
        }
        for (const auto& [code, language] : model_.common.languages) {
            Writer w;
            w.BeginObject();
            for (const auto& [key, value] : language.value.entries) {
                w.Field(key, value);
            }
            w.EndObject();
            Add(Unique("lang/" + SafeName(code), ".json"), w.Take());
        }
    }

    static std::string Geometry(const c::GeoModel& geo) {
        constexpr double kDegrees = 180.0 / std::numbers::pi;
        Writer w;
        w.BeginObject();
        w.Field("format_version", "1.12.0");
        w.Key("minecraft:geometry").BeginArray().BeginObject();
        const auto& p = geo.properties;
        w.Key("description").BeginObject();
        w.Field("identifier", p.identifier.empty() ? std::string("geometry.legacy") : p.identifier);
        w.FieldDouble("texture_width", p.texture_width);
        w.FieldDouble("texture_height", p.texture_height);
        w.FieldDouble("visible_bounds_width", p.visible_bounds_width);
        w.FieldDouble("visible_bounds_height", p.visible_bounds_height);
        if (p.visible_bounds_offset.size() == 3) {
            w.Key("visible_bounds_offset").BeginArray();
            for (double value : p.visible_bounds_offset) {
                w.Double(value);
            }
            w.EndArray();
        }
        w.FieldDouble("ysm_height_scale", p.height_scale);
        w.FieldDouble("ysm_width_scale", p.width_scale);
        if (p.extra_info) {
            const auto& info = *p.extra_info;
            w.Key("ysm_extra_info").BeginObject();
            w.Field("name", info.name);
            w.Field("tips", info.tips);
            w.Key("extra_animation_names").BeginArray();
            for (const auto& name : info.extra_animation_names) {
                w.String(name);
            }
            w.EndArray();
            w.Key("authors").BeginArray();
            for (const auto& author : info.authors) {
                w.String(author);
            }
            w.EndArray();
            w.Field("license", info.license);
            w.Field("free", info.free);
            w.EndObject();
        }
        w.EndObject();
        w.Key("bones").BeginArray();
        for (const auto& bone : geo.bones) {
            w.BeginObject();
            w.Field("name", bone.name);
            if (!bone.parent.empty()) {
                w.Field("parent", bone.parent);
            }
            // upstream convention: pivot in pixels with x negated, rotation in radians with x and y negated
            w.Key("pivot").BeginArray().Float(-bone.pivot.x).Float(bone.pivot.y).Float(bone.pivot.z).EndArray();
            w.Key("rotation")
                .BeginArray()
                .Double(-bone.rotation.x * kDegrees)
                .Double(-bone.rotation.y * kDegrees)
                .Double(bone.rotation.z * kDegrees)
                .EndArray();
            if (!bone.cubes.empty()) {
                WritePolyMesh(w, bone);
            }
            w.EndObject();
        }
        w.EndArray();
        w.EndObject().EndArray();
        w.EndObject();
        return w.Take();
    }

    static void WritePolyMesh(Writer& w, const c::Bone& bone) {
        using Key3 = std::array<float, 3>;
        using Key2 = std::array<float, 2>;
        std::map<Key3, std::size_t> positions;
        std::map<Key3, std::size_t> normals;
        std::map<Key2, std::size_t> uvs;
        std::vector<Key3> position_list;
        std::vector<Key3> normal_list;
        std::vector<Key2> uv_list;
        std::vector<std::array<std::array<std::size_t, 3>, 4>> polys;
        auto index_of = [](auto& map, auto& list, const auto& key) {
            auto [it, inserted] = map.try_emplace(key, list.size());
            if (inserted) {
                list.push_back(key);
            }
            return it->second;
        };
        for (const auto& cube : bone.cubes) {
            for (const auto& quad : cube.quads) {
                const Key3 normal{-quad.normal.x, quad.normal.y, quad.normal.z};
                const auto n = index_of(normals, normal_list, normal);
                std::array<std::array<std::size_t, 3>, 4> poly{};
                for (std::size_t v = 0; v < 4; ++v) {
                    const auto& vertex = quad.vertices[v];
                    const Key3 position{-vertex.position.x * 16.0F, vertex.position.y * 16.0F,
                                        vertex.position.z * 16.0F};
                    const Key2 uv{vertex.texture_u, vertex.texture_v};
                    poly[v] = {index_of(positions, position_list, position), n, index_of(uvs, uv_list, uv)};
                }
                polys.push_back(poly);
            }
        }
        w.Key("poly_mesh").BeginObject();
        w.Field("normalized_uvs", true);
        w.Key("positions").BeginArray();
        for (const auto& p : position_list) {
            w.BeginArray().Float(p[0]).Float(p[1]).Float(p[2]).EndArray();
        }
        w.EndArray();
        w.Key("normals").BeginArray();
        for (const auto& p : normal_list) {
            w.BeginArray().Float(p[0]).Float(p[1]).Float(p[2]).EndArray();
        }
        w.EndArray();
        w.Key("uvs").BeginArray();
        for (const auto& p : uv_list) {
            w.BeginArray().Float(p[0]).Float(p[1]).EndArray();
        }
        w.EndArray();
        w.Key("polys").BeginArray();
        for (const auto& poly : polys) {
            w.BeginArray();
            for (const auto& vertex : poly) {
                w.BeginArray().Int(std::int64_t(vertex[0])).Int(std::int64_t(vertex[1])).Int(std::int64_t(vertex[2]));
                w.EndArray();
            }
            w.EndArray();
        }
        w.EndArray();
        w.Key("futura3d_cube_faces").BeginArray();
        for (const auto& cube : bone.cubes) {
            w.Int(std::int64_t(cube.quads.size()));
        }
        w.EndArray();
        w.EndObject();
    }

    static void Molang(Writer& w, const c::MolangValue& value) {
        if (value.type == c::MolangValueType::kString) {
            w.String(value.string_value);
        } else {
            w.Double(value.double_value);
        }
    }

    static void Vec3(Writer& w, const c::MolangVec3& value) {
        w.BeginArray();
        Molang(w, value.x);
        Molang(w, value.y);
        Molang(w, value.z);
        w.EndArray();
    }

    static void KeyFrames(Writer& w, const char* channel, const std::vector<c::BoneKeyFrame>& frames) {
        if (frames.empty()) {
            return;
        }
        w.Key(channel).BeginObject();
        for (const auto& frame : frames) {
            w.Key(Writer::NumberText(TicksToSeconds(frame.start_tick))).BeginObject();
            w.Key("pre");
            Vec3(w, frame.pre);
            if (frame.post) {
                w.Key("post");
                Vec3(w, *frame.post);
            }
            if (frame.easing == c::EasingType::kCatmullRom) {
                w.Field("lerp_mode", "catmullrom");
            }
            w.EndObject();
        }
        w.EndObject();
    }

    static std::string Animations(const c::AnimationFile& file) {
        Writer w;
        w.BeginObject();
        w.Field("format_version", "1.8.0");
        w.Key("animations").BeginObject();
        for (const auto& animation : file.animations) {
            w.Key(animation.name).BeginObject();
            switch (animation.loop) {
                case c::LoopType::kLoop:
                    w.Field("loop", true);
                    break;
                case c::LoopType::kHoldOnLastFrame:
                    w.Field("loop", "hold_on_last_frame");
                    break;
                case c::LoopType::kPlayOnce:
                    break;
            }
            // -1: not set; +infinity: the old importer's "no length" sentinel. Both leave it to the loader.
            if (animation.length >= 0 && std::isfinite(animation.length)) {
                w.FieldDouble("animation_length", TicksToSeconds(animation.length));
            }
            if (animation.start_delay) {
                w.Key("start_delay");
                Molang(w, *animation.start_delay);
            }
            if (animation.loop_delay) {
                w.Key("loop_delay");
                Molang(w, *animation.loop_delay);
            }
            if (animation.blend_weight) {
                w.Key("blend_weight");
                Molang(w, *animation.blend_weight);
            }
            if (animation.override_previous) {
                w.Field("override_previous_animation", *animation.override_previous);
            }
            if (!animation.bones.empty()) {
                w.Key("bones").BeginObject();
                for (const auto& bone : animation.bones) {
                    w.Key(bone.bone_name).BeginObject();
                    KeyFrames(w, "rotation", bone.rotations);
                    KeyFrames(w, "position", bone.positions);
                    KeyFrames(w, "scale", bone.scales);
                    w.EndObject();
                }
                w.EndObject();
            }
            if (!animation.instructions.empty()) {
                w.Key("timeline").BeginObject();
                for (const auto& frame : animation.instructions) {
                    w.Key(Writer::NumberText(TicksToSeconds(frame.start_tick))).BeginArray();
                    for (const auto& line : frame.data) {
                        w.String(line);
                    }
                    w.EndArray();
                }
                w.EndObject();
            }
            if (!animation.sounds.empty()) {
                // several sounds at one time: one array per time, as the loader reads
                std::vector<std::pair<double, std::vector<const std::string*>>> times;
                for (const auto& frame : animation.sounds) {
                    if (times.empty() || times.back().first != frame.start_tick) {
                        times.emplace_back(frame.start_tick, std::vector<const std::string*>{});
                    }
                    times.back().second.push_back(&frame.data);
                }
                w.Key("sound_effects").BeginObject();
                for (const auto& [tick, effects] : times) {
                    w.Key(Writer::NumberText(TicksToSeconds(tick))).BeginArray();
                    for (const auto* effect : effects) {
                        w.BeginObject().Field("effect", *effect).EndObject();
                    }
                    w.EndArray();
                }
                w.EndObject();
            }
            w.EndObject();
        }
        w.EndObject();
        w.EndObject();
        return w.Take();
    }

    static std::string Controllers(const c::AnimationControllerFile& file) {
        Writer w;
        w.BeginObject();
        w.Field("format_version", "1.10.0");
        w.Key("animation_controllers").BeginObject();
        for (const auto& [name, controller] : file.controllers) {
            w.Key(name).BeginObject();
            if (!controller.initial_state.empty()) {
                w.Field("initial_state", controller.initial_state);
            }
            w.Key("states").BeginObject();
            for (const auto& [state_name, state] : controller.states) {
                w.Key(state_name).BeginObject();
                if (!state.animations.empty()) {
                    w.Key("animations").BeginArray();
                    for (const auto& entry : state.animations) {
                        if (entry.condition.empty()) {
                            w.String(entry.name);
                        } else {
                            w.BeginObject().Field(entry.name, entry.condition).EndObject();
                        }
                    }
                    w.EndArray();
                }
                if (!state.transitions.empty()) {
                    w.Key("transitions").BeginArray();
                    for (const auto& transition : state.transitions) {
                        w.BeginObject().Field(transition.destination, transition.condition).EndObject();
                    }
                    w.EndArray();
                }
                for (const auto& [key, lines] : {std::pair{"on_entry", &state.on_entry},
                                                 std::pair{"on_exit", &state.on_exit}}) {
                    if (!lines->empty()) {
                        w.Key(key).BeginArray();
                        for (const auto& line : *lines) {
                            w.String(line);
                        }
                        w.EndArray();
                    }
                }
                const auto& blend = state.blend_transition;
                if (!blend.points.empty()) {
                    w.Key("blend_transition").BeginObject();
                    for (const auto& [time, weight] : blend.points) {
                        w.Key(Writer::NumberText(time)).Float(weight);
                    }
                    w.EndObject();
                } else if (blend.linear_length) {
                    w.FieldFloat("blend_transition", *blend.linear_length);
                }
                if (state.blend_via_shortest_path) {
                    w.Field("blend_via_shortest_path", true);
                }
                if (!state.sound_effects.empty()) {
                    w.Key("sound_effects").BeginArray();
                    for (const auto& effect : state.sound_effects) {
                        w.BeginObject().Field("effect", effect).EndObject();
                    }
                    w.EndArray();
                }
                w.EndObject();
            }
            w.EndObject();
            w.EndObject();
        }
        w.EndObject();
        w.EndObject();
        return w.Take();
    }
};

void PutU8(std::string& out, std::uint8_t value) { out += static_cast<char>(value); }
void PutU16(std::string& out, std::uint16_t value) {
    out += static_cast<char>(value >> 8);
    out += static_cast<char>(value & 0xFF);
}
void PutU32(std::string& out, std::uint32_t value) {
    PutU16(out, static_cast<std::uint16_t>(value >> 16));
    PutU16(out, static_cast<std::uint16_t>(value & 0xFFFF));
}
}  // namespace

absl::StatusOr<ExportedModel> Export(c::LegacyModel& model) { return Exporter(model).Run(); }

std::string Serialize(const ExportedModel& model) {
    std::size_t total = 16;
    for (const auto& file : model.files) {
        total += 15 + file.path.size() + file.bytes.size();
    }
    std::string out;
    out.reserve(total);
    out += "F3DL";
    PutU32(out, 1);
    PutU32(out, model.inner_version);
    PutU32(out, static_cast<std::uint32_t>(model.files.size()));
    for (const auto& file : model.files) {
        PutU8(out, static_cast<std::uint8_t>(file.kind));
        PutU16(out, static_cast<std::uint16_t>(std::min<std::size_t>(file.path.size(), 0xFFFF)));
        out.append(file.path, 0, std::min<std::size_t>(file.path.size(), 0xFFFF));
        if (file.kind == FileKind::kRgba) {
            PutU32(out, file.width);
            PutU32(out, file.height);
        }
        PutU32(out, static_cast<std::uint32_t>(file.bytes.size()));
        out += file.bytes;
    }
    return out;
}
}  // namespace futura::legacy
