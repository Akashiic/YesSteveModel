// 3D Futura Engine: the JNI entry of the legacy loader, bound to com.akashic.futura.natives.LegacyNative. Replaces
// upstream modules/lib-jni/src/legacy (which hands upstream's protobuf result to com.elfmcys.ysm classes).
#include <jni.h>

#include <exception>
#include <new>
#include <string>
#include <vector>

#include <v3/codec/envelope.h>
#include <v3/container/decoder.h>

#include "exporter.h"

namespace {
constexpr jint kAbiVersion = 1;

void Throw(JNIEnv* env, const char* type, const std::string& message) {
    jclass cls = env->FindClass(type);
    if (cls != nullptr) {
        env->ThrowNew(cls, message.c_str());
    }
}
}  // namespace

extern "C" {
JNIEXPORT jint JNICALL Java_com_akashic_futura_natives_LegacyNative_nAbiVersion(JNIEnv*, jclass) {
    return kAbiVersion;
}

JNIEXPORT jbyteArray JNICALL Java_com_akashic_futura_natives_LegacyNative_nConvert(JNIEnv* env, jclass,
                                                                                   jbyteArray source) {
    if (source == nullptr) {
        Throw(env, "java/lang/NullPointerException", "source");
        return nullptr;
    }
    try {
        const jsize length = env->GetArrayLength(source);
        if (static_cast<std::uint64_t>(length) > ysm::legacy::v3::codec::kLegacySourceByteLimit) {
            Throw(env, "java/io/IOException", "Legacy model exceeds the size limit");
            return nullptr;
        }
        std::vector<ysm::Byte> bytes(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(source, 0, length, reinterpret_cast<jbyte*>(bytes.data()));

        ysm::legacy::v3::codec::ContainerReader reader{ysm::BufferViewR(bytes.data(), bytes.size())};
        if (auto status = reader.Initialize(); !status.ok()) {
            Throw(env, "java/io/IOException", "Legacy envelope: " + std::string(status.message()));
            return nullptr;
        }
        auto model = ysm::legacy::v3::container::Decode(reader);
        if (!model.ok()) {
            Throw(env, "java/io/IOException", "Legacy container: " + std::string(model.status().message()));
            return nullptr;
        }
        auto exported = futura::legacy::Export(*model);
        if (!exported.ok()) {
            Throw(env, "java/io/IOException", "Legacy export: " + std::string(exported.status().message()));
            return nullptr;
        }
        const std::string out = futura::legacy::Serialize(*exported);
        if (out.size() > 0x7FFFFFF0U) {
            Throw(env, "java/io/IOException", "Legacy export is too large");
            return nullptr;
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
        if (result == nullptr) {
            return nullptr;  // OutOfMemoryError pending
        }
        env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte*>(out.data()));
        return result;
    } catch (const std::bad_alloc&) {
        Throw(env, "java/io/IOException", "Legacy import exhausted native memory");
    } catch (const std::exception&) {
        Throw(env, "java/io/IOException", "Legacy import failed internally");
    } catch (...) {
        Throw(env, "java/io/IOException", "Legacy import failed internally");
    }
    return nullptr;
}
}
