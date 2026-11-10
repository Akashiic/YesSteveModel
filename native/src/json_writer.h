// 3D Futura Engine: a minimal streaming JSON writer for the legacy exporter. Strings are written as UTF-8 with the
// characters JSON requires escaped; numbers in the shortest form that reads back to the same float or double.
#pragma once

#include <charconv>
#include <cmath>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace futura::json {
class Writer {
   public:
    Writer& BeginObject() {
        Separate();
        out_ += '{';
        first_.push_back(true);
        return *this;
    }
    Writer& EndObject() {
        first_.pop_back();
        out_ += '}';
        return *this;
    }
    Writer& BeginArray() {
        Separate();
        out_ += '[';
        first_.push_back(true);
        return *this;
    }
    Writer& EndArray() {
        first_.pop_back();
        out_ += ']';
        return *this;
    }
    Writer& Key(std::string_view key) {
        Separate();
        Quote(key);
        out_ += ':';
        after_key_ = true;
        return *this;
    }
    Writer& String(std::string_view value) {
        Separate();
        Quote(value);
        return *this;
    }
    Writer& Bool(bool value) {
        Separate();
        out_ += value ? "true" : "false";
        return *this;
    }
    Writer& Int(std::int64_t value) {
        Separate();
        out_ += std::to_string(value);
        return *this;
    }
    Writer& Float(float value) {
        Separate();
        AppendNumber(value);
        return *this;
    }
    Writer& Double(double value) {
        Separate();
        AppendNumber(value);
        return *this;
    }

    // Convenience: "key": value.
    Writer& Field(std::string_view key, std::string_view value) { return Key(key).String(value); }
    Writer& Field(std::string_view key, const char* value) { return Key(key).String(value); }
    Writer& Field(std::string_view key, bool value) { return Key(key).Bool(value); }
    Writer& FieldFloat(std::string_view key, float value) { return Key(key).Float(value); }
    Writer& FieldDouble(std::string_view key, double value) { return Key(key).Double(value); }

    [[nodiscard]] std::string Take() { return std::move(out_); }

    template <typename Number>
    static std::string NumberText(Number value) {
        if (!std::isfinite(value)) {
            return "0";
        }
        char buffer[64];
        auto result = std::to_chars(buffer, buffer + sizeof(buffer), value);
        return std::string(buffer, result.ptr);
    }

   private:
    std::string out_;
    std::vector<bool> first_;
    bool after_key_ = false;

    void Separate() {
        if (after_key_) {
            after_key_ = false;
            return;
        }
        if (!first_.empty()) {
            if (!first_.back()) {
                out_ += ',';
            }
            first_.back() = false;
        }
    }

    template <typename Number>
    void AppendNumber(Number value) {
        out_ += NumberText(value);
    }

    void Quote(std::string_view text) {
        static constexpr char kHex[] = "0123456789abcdef";
        out_ += '"';
        for (const char c : text) {
            const auto u = static_cast<unsigned char>(c);
            switch (c) {
                case '"':
                    out_ += "\\\"";
                    break;
                case '\\':
                    out_ += "\\\\";
                    break;
                case '\n':
                    out_ += "\\n";
                    break;
                case '\r':
                    out_ += "\\r";
                    break;
                case '\t':
                    out_ += "\\t";
                    break;
                default:
                    if (u < 0x20) {
                        out_ += "\\u00";
                        out_ += kHex[u >> 4];
                        out_ += kHex[u & 15];
                    } else {
                        out_ += c;
                    }
            }
        }
        out_ += '"';
    }
};
}  // namespace futura::json
