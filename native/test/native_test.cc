// 3D Futura Engine: tests of the slim legacy loader, without a test framework (ctest runs the executable).
//  1. The envelope (CityHash, XChaCha with the historical parameter updates, mt19937 XOR, dirty zstd) against
//     upstream's frozen vector test/data/legacy_v3_dynamic_vector.ysm (see its .md).
//  2. The decoder and the exporter on a synthetic v32 plaintext written here with upstream's wire layout
//     (FixtureWriter, adapted from upstream modules/test/src/legacy_historical_decoder_test.cc, Apache-2.0).
// With a path argument, the serialized export of the synthetic model is written there (the Java tests read it).
#include <bit>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <random>
#include <string>
#include <string_view>
#include <vector>

#include <v3/codec/envelope.h>
#include <v3/container/decoder.h>

#include "../src/exporter.h"

namespace {
int failures = 0;

void Check(bool ok, const std::string& what) {
    if (!ok) {
        ++failures;
        std::fprintf(stderr, "FAIL: %s\n", what.c_str());
    }
}

class FixtureWriter {
   public:
    void FixedU32(std::uint32_t value) {
        for (int i = 0; i < 4; ++i) {
            bytes.push_back(static_cast<ysm::Byte>(value >> (i * 8)));
        }
    }
    void VarU32(std::uint32_t value) {
        do {
            auto byte = static_cast<ysm::Byte>(value & 0x7FU);
            value >>= 7U;
            bytes.push_back(value == 0 ? byte : static_cast<ysm::Byte>(byte | 0x80U));
        } while (value != 0);
    }
    void Bool(bool value) { bytes.push_back(value ? 1 : 0); }
    void Float(float value) { FixedU32(std::bit_cast<std::uint32_t>(value)); }
    void String(std::string_view value) {
        VarU32(static_cast<std::uint32_t>(value.size()));
        bytes.insert(bytes.end(), value.begin(), value.end());
    }
    void Bytes(const std::vector<ysm::Byte>& value) {
        VarU32(static_cast<std::uint32_t>(value.size()));
        bytes.insert(bytes.end(), value.begin(), value.end());
    }
    void Vec3(float x, float y, float z) {
        Float(x);
        Float(y);
        Float(z);
    }
    void MolangNumber(float value) {
        VarU32(1);
        Float(value);
    }
    void MolangString(std::string_view value) {
        VarU32(2);
        String(value);
    }
    std::vector<ysm::Byte> bytes;
};

constexpr std::uint32_t kVersion = 32;

void WriteGeoProperties(FixtureWriter& w) {
    w.String("geometry.synthetic");
    w.Float(64.0F);  // texture height
    w.Float(64.0F);  // texture width
    w.Float(2.0F);
    w.Float(3.0F);
    w.VarU32(3);
    w.Float(0.0F);
    w.Float(1.5F);
    w.Float(0.0F);
    w.Float(0.7F);
    w.Float(0.7F);
    w.VarU32(0);  // extra info
    w.VarU32(0);  // script variables
    w.VarU32(0);  // initialize
    w.VarU32(0);  // pre-animation
}

// One bone "root" with one cube of one quad (a 1x1 block face at z = 0.25, blocks with x negated), pivot (-1, 2, 3)
// pixels in the stored convention and a rest rotation of 0.5 rad around x.
void WriteGeo(FixtureWriter& w, bool with_cube) {
    w.VarU32(1);   // bones
    w.String("");  // parent
    if (with_cube) {
        w.VarU32(1);  // cubes
        w.VarU32(1);  // quads
        w.Vec3(0.0F, 0.0F, 1.0F);
        const float vertices[4][5] = {
            {-0.0625F, 0.0F, 0.25F, 0.0F, 0.0F},
            {-0.125F, 0.0F, 0.25F, 0.125F, 0.0F},
            {-0.125F, 0.0625F, 0.25F, 0.125F, 0.25F},
            {-0.0625F, 0.0625F, 0.25F, 0.0F, 0.25F},
        };
        for (const auto& v : vertices) {
            for (float component : v) {
                w.Float(component);
            }
        }
        w.Bool(false);
        w.Bool(false);
        w.Bool(false);
    } else {
        w.VarU32(0);
    }
    w.String(with_cube ? "root" : "RightArm");
    for (int i = 0; i < 5; ++i) {
        w.Bool(false);
    }
    w.Vec3(-1.0F, 2.0F, 3.0F);
    w.Vec3(with_cube ? 0.5F : 0.0F, 0.0F, 0.0F);
    WriteGeoProperties(w);
}

void WriteAnimationFile(FixtureWriter& w) {
    w.VarU32(1);  // animations
    w.String("idle");
    w.Float(20.0F);  // length, ticks
    w.VarU32(1);     // loop
    w.VarU32(0);     // start delay
    w.VarU32(0);     // loop delay
    w.VarU32(0);     // blend weight
    w.VarU32(0);     // override previous
    w.VarU32(1);     // bones
    w.String("root");
    w.VarU32(2);  // rotation key frames
    w.Float(0.0F);
    w.VarU32(1);  // linear
    w.MolangNumber(0.0F);
    w.MolangNumber(0.0F);
    w.MolangNumber(0.0F);
    w.VarU32(0);  // no post
    w.Float(10.0F);
    w.VarU32(2);  // catmull-rom
    w.MolangNumber(10.0F);
    w.MolangString("q.anim_time * 2");
    w.MolangNumber(0.0F);
    w.VarU32(1);  // post
    w.MolangNumber(-5.0F);
    w.MolangNumber(0.0F);
    w.MolangNumber(0.0F);
    w.VarU32(0);  // position
    w.VarU32(0);  // scale
    w.VarU32(1);  // instructions
    w.VarU32(1);
    w.String("v.a = 1;");
    w.Float(5.0F);
    w.VarU32(1);  // sound key frames
    w.String("hello");
    w.Float(2.0F);
}

void WriteControllerFile(FixtureWriter& w) {
    w.VarU32(1);  // controllers
    w.String("controller.animation.synthetic");
    w.String("default");
    w.VarU32(2);  // states
    w.String("default");
    w.VarU32(1);
    w.String("idle");
    w.String("");
    w.VarU32(1);
    w.String("other");
    w.String("q.is_on_ground");
    w.VarU32(1);
    w.String("v.e = 1;");
    w.VarU32(0);
    w.VarU32(1);  // linear blend present
    w.Float(0.25F);
    w.Bool(false);
    w.VarU32(1);
    w.String("hello");
    w.String("other");
    w.VarU32(0);
    w.VarU32(0);
    w.VarU32(0);
    w.VarU32(0);
    w.VarU32(0);  // segmented blend
    w.VarU32(1);  // points
    w.Float(0.5F);
    w.Float(1.0F);
    w.Bool(true);
    w.VarU32(0);
}

std::vector<ysm::Byte> MakeSyntheticModel() {
    FixtureWriter w;
    w.FixedU32(kVersion);
    // common assets
    w.VarU32(0);  // sounds
    w.VarU32(1);  // functions
    w.String("hello@player_init");
    w.String("");
    w.String("v.x = 1;");
    w.VarU32(1);  // languages
    w.String("en_us");
    w.String("");
    w.VarU32(1);
    w.String("model.name");
    w.String("Synthetic \"quoted\"");
    w.VarU32(0);  // vehicles
    w.VarU32(0);  // projectiles
    // player
    w.VarU32(1);
    w.VarU32(1);  // animation files
    w.VarU32(1);  // main
    w.String("");
    WriteAnimationFile(w);
    w.VarU32(1);  // controller files
    w.String("synthetic");
    w.String("");
    WriteControllerFile(w);
    w.VarU32(1);  // textures
    w.String("skin");
    w.String("");
    w.Bytes({0x10, 0x20, 0x30, 0xFF});
    w.VarU32(1);
    w.VarU32(1);
    w.VarU32(1);  // RGBA
    w.VarU32(1);  // frames
    w.VarU32(0);  // PBR
    w.VarU32(2);  // geos
    w.VarU32(1);
    w.String("");
    WriteGeo(w, true);
    w.VarU32(2);
    w.String("");
    WriteGeo(w, false);
    // info
    w.String("000102030405060708090a0b0c0d0e0f");
    w.VarU32(1);  // metadata
    w.String("Synthetic");
    w.String("tips");
    w.String("CC0-1.0");
    w.String("");
    w.VarU32(1);
    w.String("Author");
    w.String("model");
    w.VarU32(0);
    w.String("");
    w.VarU32(0);  // links
    w.Float(0.9F);
    w.Float(0.8F);
    w.VarU32(1);  // extra animations
    w.String("extra0");
    w.String("Wave");
    w.VarU32(0);  // buttons
    w.VarU32(0);  // classify
    w.String("skin");
    w.String("idle");
    w.Bool(true);   // free
    w.Bool(false);  // render layers first
    w.Bool(false);  // all cutout
    w.Bool(false);  // disable preview rotation
    w.Bool(false);  // gui no lighting
    w.Bool(true);   // merge multiline expr
    w.String("");
    w.String("");
    w.VarU32(0);   // avatars
    w.VarU32(0);   // gui images
    w.VarU32(19);  // origin version: 2.4.2-snapshot-32
    w.VarU32(0);   // export info
    w.String("");
    return w.bytes;
}

std::vector<ysm::Byte> ReadFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

const futura::legacy::ExportedFile* Find(const futura::legacy::ExportedModel& model, std::string_view path) {
    for (const auto& file : model.files) {
        if (file.path == path) {
            return &file;
        }
    }
    return nullptr;
}

bool Contains(const futura::legacy::ExportedFile* file, std::string_view text) {
    return file != nullptr && file->bytes.find(text) != std::string::npos;
}

void TestEnvelope(const std::string& data_dir) {
    const auto source = ReadFile(data_dir + "/legacy_v3_dynamic_vector.ysm");
    Check(source.size() == 12'095, "envelope fixture present");
    ysm::legacy::v3::codec::ContainerReader reader{ysm::BufferViewR(source.data(), source.size())};
    auto status = reader.Initialize();
    Check(status.ok(), "envelope initializes: " + std::string(status.message()));
    std::vector<ysm::Byte> plaintext(12'004);
    Check(reader.ReadExact(plaintext) && reader.Finish(), "envelope reads 12004 bytes");
    Check(plaintext[0] == 1 && plaintext[1] == 0 && plaintext[2] == 0 && plaintext[3] == 0, "plaintext version 1");
    std::mt19937_64 generator(0xD15EA5E);
    bool same = true;
    for (std::size_t i = 4; i < plaintext.size(); ++i) {
        same = same && plaintext[i] == static_cast<ysm::Byte>(generator());
    }
    Check(same, "plaintext matches the mt19937_64 sequence");
}

void TestExport(const std::string& output) {
    const auto plaintext = MakeSyntheticModel();
    auto model = ysm::legacy::v3::container::Decode(ysm::BufferViewR(plaintext.data(), plaintext.size()));
    Check(model.ok(), "synthetic v32 decodes: " + std::string(model.ok() ? "" : model.status().message()));
    if (!model.ok()) {
        return;
    }
    auto exported = futura::legacy::Export(*model);
    Check(exported.ok(), "synthetic v32 exports");
    if (!exported.ok()) {
        return;
    }
    const auto* manifest = Find(*exported, "ysm.json");
    Check(Contains(manifest, R"("spec":2)"), "manifest spec 2");
    Check(Contains(manifest, R"("name":"Synthetic")"), "manifest name");
    Check(Contains(manifest, R"("originVer":"2.4.2-snapshot-32")"), "originVer from the release table");
    Check(Contains(manifest, R"("main":"models/main.json")"), "main geometry path");
    Check(Contains(manifest, R"("main":"animations/main.animation.json")"), "main animation path");
    Check(Contains(manifest, R"("animation_controllers":["controller/synthetic.json"])"), "controller path");
    Check(Contains(manifest, R"("texture":[{"uv":"textures/skin.png"}])"), "texture path");
    Check(Contains(manifest, R"("extra_animation":{"extra0":"Wave"})"), "extra animations");
    Check(Contains(manifest, R"("merge_multiline_expr":true)"), "merge_multiline_expr");
    const auto* geo = Find(*exported, "models/main.json");
    Check(Contains(geo, R"("pivot":[1,2,3])"), "pivot back in Bedrock units");
    Check(Contains(geo, R"("rotation":[-28.64788975654116,-0,0])"), "rest rotation in degrees");
    Check(Contains(geo, R"("positions":[[1,0,4],[2,0,4],[2,1,4],[1,1,4]])"), "poly_mesh positions in pixels");
    Check(Contains(geo, R"("normals":[[-0,0,1]])"), "poly_mesh normal");
    Check(Contains(geo, R"("uvs":[[0,0],[0.125,0],[0.125,0.25],[0,0.25]])"), "poly_mesh uvs");
    Check(Contains(geo, R"("futura3d_cube_faces":[1])"), "cube face counts");
    const auto* animation = Find(*exported, "animations/main.animation.json");
    Check(Contains(animation, R"("idle":{"loop":true,"animation_length":1)"), "loop and length in seconds");
    Check(Contains(animation,
                   R"("0.5":{"pre":[10,"q.anim_time * 2",0],"post":[-5,0,0],"lerp_mode":"catmullrom"})"),
          "key frame with post and catmull-rom");
    Check(Contains(animation, R"("timeline":{"0.25":["v.a = 1;"]})"), "timeline");
    Check(Contains(animation, R"("sound_effects":{"0.1":[{"effect":"hello"}]})"), "sound key frames");
    const auto* controller = Find(*exported, "controller/synthetic.json");
    Check(Contains(controller, R"("transitions":[{"other":"q.is_on_ground"}])"), "controller transitions");
    Check(Contains(controller, R"("blend_transition":0.25)"), "linear blend");
    Check(Contains(controller, R"("blend_transition":{"0.5":1})"), "segmented blend");
    Check(Contains(controller, R"("sound_effects":[{"effect":"hello"}])"), "state sound effects");
    Check(Contains(Find(*exported, "functions/hello@player_init.molang"), "v.x = 1;"), "function file");
    Check(Contains(Find(*exported, "lang/en_us.json"), R"({"model.name":"Synthetic \"quoted\""})"),
          "language file with escaping");
    const auto* skin = Find(*exported, "textures/skin.png");
    Check(skin != nullptr && skin->kind == futura::legacy::FileKind::kRgba && skin->width == 1 &&
              skin->bytes.size() == 4,
          "RGBA texture handed to Java");
    if (!output.empty()) {
        std::ofstream out(output, std::ios::binary);
        const auto bytes = futura::legacy::Serialize(*exported);
        out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    }
}
}  // namespace

int main(int argc, char** argv) {
    const std::string data_dir = argc > 1 ? argv[1] : "test/data";
    TestEnvelope(data_dir);
    TestExport(argc > 2 ? argv[2] : "");
    if (failures == 0) {
        std::puts("native_test: all checks passed");
    }
    return failures == 0 ? 0 : 1;
}
