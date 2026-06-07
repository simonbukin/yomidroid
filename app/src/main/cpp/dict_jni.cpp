// JNI bridge for the Hoshidicts dictionary backend (C++23).
//
// Mirrors the structure of llm_inference.cpp: a thin C ABI surface that the
// Kotlin `HoshiDicts` wrapper calls. Results cross the boundary as JSON encoded
// in UTF-8 bytes (returned as Java strings built from UTF-16 to dodge JNI's
// modified-UTF-8 encoding for supplementary-plane kanji). The Yomitan glossary
// is already valid JSON, so it is embedded verbatim as a nested value rather
// than re-escaped as a string.
//
// Dictionary lookups are read-only against memory-mapped files and run
// concurrently; rebuilding the dictionary set (reset + add_*_dict) mutates the
// query, so reads take a shared lock and rebuilds take a unique lock.

#include <android/log.h>
#include <jni.h>

#include <cstdio>
#include <iterator>
#include <memory>
#include <shared_mutex>
#include <string>
#include <string_view>
#include <vector>

#include <utf8.h>

#include "hoshidicts/deinflector.hpp"
#include "hoshidicts/importer.hpp"
#include "hoshidicts/lookup.hpp"
#include "hoshidicts/query.hpp"

#define LOG_TAG "YomidroidDict"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::shared_mutex g_mutex;
std::unique_ptr<DictionaryQuery> g_query;
std::unique_ptr<Deinflector> g_deinf;

void ensure_deinf() {
    if (!g_deinf) g_deinf = std::make_unique<Deinflector>();
}

std::string jstring_to_utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jsize len = env->GetStringLength(s);
    const jchar* chars = env->GetStringChars(s, nullptr);
    std::string out;
    try {
        utf8::utf16to8(chars, chars + len, std::back_inserter(out));
    } catch (...) {
        out.clear();
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

jstring utf8_to_jstring(JNIEnv* env, const std::string& s) {
    std::vector<jchar> utf16;
    try {
        utf8::utf8to16(s.begin(), s.end(), std::back_inserter(utf16));
    } catch (...) {
        utf16.clear();
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

std::string json_escape(std::string_view s) {
    std::string o;
    o.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\b': o += "\\b"; break;
            case '\f': o += "\\f"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    o += buf;
                } else {
                    o += c;
                }
        }
    }
    return o;
}

void append_kv_str(std::string& out, const char* key, std::string_view val, bool trailing_comma = true) {
    out += '"';
    out += key;
    out += "\":\"";
    out += json_escape(val);
    out += '"';
    if (trailing_comma) out += ',';
}

// Serialize one term (optionally with lookup match context) into the JSON the
// Kotlin side maps to one-or-more DictionaryEntry objects (one per glossary).
void append_term(std::string& out, const std::string& matched, const std::string& deinflected,
                 const std::vector<TransformGroup>& trace, int preprocessor_steps, const TermResult& t) {
    out += '{';
    append_kv_str(out, "matched", matched);
    append_kv_str(out, "deinflected", deinflected);
    out += "\"preprocessorSteps\":" + std::to_string(preprocessor_steps) + ',';

    // Deinflection trace: one compact display name + full description per step.
    out += "\"steps\":[";
    for (size_t i = 0; i < trace.size(); ++i) {
        if (i) out += ',';
        out += '{';
        append_kv_str(out, "name", trace[i].name);
        append_kv_str(out, "description", trace[i].description, false);
        out += '}';
    }
    out += "],";

    append_kv_str(out, "expression", t.expression);
    append_kv_str(out, "reading", t.reading);
    append_kv_str(out, "rules", t.rules);

    out += "\"glossaries\":[";
    for (size_t i = 0; i < t.glossaries.size(); ++i) {
        const auto& g = t.glossaries[i];
        if (i) out += ',';
        out += '{';
        append_kv_str(out, "dict", g.dict_name);
        append_kv_str(out, "definitionTags", g.definition_tags);
        append_kv_str(out, "termTags", g.term_tags);
        out += "\"glossary\":";
        out += g.glossary.empty() ? std::string("[]") : g.glossary;  // raw JSON, embedded verbatim
        out += '}';
    }
    out += "],";

    out += "\"frequencies\":[";
    for (size_t i = 0; i < t.frequencies.size(); ++i) {
        const auto& f = t.frequencies[i];
        if (i) out += ',';
        out += '{';
        append_kv_str(out, "dict", f.dict_name);
        out += "\"values\":[";
        for (size_t j = 0; j < f.frequencies.size(); ++j) {
            if (j) out += ',';
            out += std::to_string(f.frequencies[j].value);
        }
        out += "],\"display\":[";
        for (size_t j = 0; j < f.frequencies.size(); ++j) {
            if (j) out += ',';
            out += '"' + json_escape(f.frequencies[j].display_value) + '"';
        }
        out += "]}";
    }
    out += "],";

    out += "\"pitches\":[";
    for (size_t i = 0; i < t.pitches.size(); ++i) {
        const auto& p = t.pitches[i];
        if (i) out += ',';
        out += '{';
        append_kv_str(out, "dict", p.dict_name);
        out += "\"positions\":[";
        for (size_t j = 0; j < p.pitch_positions.size(); ++j) {
            if (j) out += ',';
            out += std::to_string(p.pitch_positions[j]);
        }
        out += "]}";
    }
    out += "]}";
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeImport(
    JNIEnv* env, jobject, jstring jzip, jstring joutput, jboolean lowRam) {
    const std::string zip = jstring_to_utf8(env, jzip);
    const std::string output = jstring_to_utf8(env, joutput);

    ImportResult r;
    try {
        r = dictionary_importer::import(zip, output, lowRam == JNI_TRUE);
    } catch (const std::exception& e) {
        r.success = false;
        r.errors.push_back(e.what());
        LOGE("Import threw: %s", e.what());
    } catch (...) {
        r.success = false;
        r.errors.push_back("unknown native exception");
        LOGE("Import threw unknown exception");
    }

    std::string out = "{";
    out += "\"success\":" + std::string(r.success ? "true" : "false") + ',';
    append_kv_str(out, "title", r.title);
    out += "\"termCount\":" + std::to_string(r.term_count) + ',';
    out += "\"metaCount\":" + std::to_string(r.meta_count) + ',';
    out += "\"freqCount\":" + std::to_string(r.freq_count) + ',';
    out += "\"pitchCount\":" + std::to_string(r.pitch_count) + ',';
    out += "\"mediaCount\":" + std::to_string(r.media_count) + ',';
    out += "\"errors\":[";
    for (size_t i = 0; i < r.errors.size(); ++i) {
        if (i) out += ',';
        out += '"' + json_escape(r.errors[i]) + '"';
    }
    out += "]}";
    return utf8_to_jstring(env, out);
}

JNIEXPORT void JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeReset(JNIEnv*, jobject) {
    std::unique_lock lock(g_mutex);
    g_query = std::make_unique<DictionaryQuery>();
    ensure_deinf();
    LOGI("Dictionary query reset");
}

// Rebuild the entire dictionary set atomically under a single write lock, so a
// concurrent lookup never observes a half-built query (reset + adds as separate
// locked calls would leave a window where the query is empty/partial).
JNIEXPORT void JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeLoad(
    JNIEnv* env, jobject, jobjectArray termPaths, jobjectArray freqPaths, jobjectArray pitchPaths) {
    std::unique_lock lock(g_mutex);
    auto query = std::make_unique<DictionaryQuery>();
    ensure_deinf();

    auto add_all = [&](jobjectArray arr, void (DictionaryQuery::*fn)(const std::string&)) {
        if (!arr) return;
        const jsize n = env->GetArrayLength(arr);
        for (jsize i = 0; i < n; ++i) {
            jstring s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
            (query.get()->*fn)(jstring_to_utf8(env, s));
            env->DeleteLocalRef(s);
        }
    };
    add_all(termPaths, &DictionaryQuery::add_term_dict);
    add_all(freqPaths, &DictionaryQuery::add_freq_dict);
    add_all(pitchPaths, &DictionaryQuery::add_pitch_dict);

    g_query = std::move(query);
    LOGI("Dictionary set rebuilt");
}

JNIEXPORT void JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeAddTermDict(
    JNIEnv* env, jobject, jstring jpath) {
    std::unique_lock lock(g_mutex);
    if (g_query) g_query->add_term_dict(jstring_to_utf8(env, jpath));
}

JNIEXPORT void JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeAddFreqDict(
    JNIEnv* env, jobject, jstring jpath) {
    std::unique_lock lock(g_mutex);
    if (g_query) g_query->add_freq_dict(jstring_to_utf8(env, jpath));
}

JNIEXPORT void JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeAddPitchDict(
    JNIEnv* env, jobject, jstring jpath) {
    std::unique_lock lock(g_mutex);
    if (g_query) g_query->add_pitch_dict(jstring_to_utf8(env, jpath));
}

JNIEXPORT jstring JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeLookup(
    JNIEnv* env, jobject, jstring jtext, jint maxResults, jint scanLength) {
    const std::string text = jstring_to_utf8(env, jtext);
    std::shared_lock lock(g_mutex);
    if (!g_query || !g_deinf) return utf8_to_jstring(env, "[]");

    std::string out = "[";
    try {
        Lookup lk(*g_query, *g_deinf);
        auto results = lk.lookup(text, maxResults, static_cast<size_t>(scanLength));
        for (size_t i = 0; i < results.size(); ++i) {
            if (i) out += ',';
            const auto& r = results[i];
            append_term(out, r.matched, r.deinflected, r.trace, r.preprocessor_steps, r.term);
        }
    } catch (const std::exception& e) {
        LOGE("lookup threw: %s", e.what());
    } catch (...) {
        LOGE("lookup threw unknown exception");
    }
    out += ']';
    return utf8_to_jstring(env, out);
}

JNIEXPORT jstring JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeQuery(
    JNIEnv* env, jobject, jstring jexpr) {
    const std::string expr = jstring_to_utf8(env, jexpr);
    std::shared_lock lock(g_mutex);
    if (!g_query) return utf8_to_jstring(env, "[]");

    std::string out = "[";
    try {
        auto results = g_query->query(expr);
        for (size_t i = 0; i < results.size(); ++i) {
            if (i) out += ',';
            const auto& t = results[i];
            // Exact query: matched == deinflected == the queried expression, no
            // deconjugation steps.
            append_term(out, expr, expr, {}, 0, t);
        }
    } catch (const std::exception& e) {
        LOGE("query threw: %s", e.what());
    } catch (...) {
        LOGE("query threw unknown exception");
    }
    out += ']';
    return utf8_to_jstring(env, out);
}

JNIEXPORT jstring JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeGetStyles(JNIEnv* env, jobject) {
    std::shared_lock lock(g_mutex);
    std::string out = "{";
    if (g_query) {
        try {
            auto styles = g_query->get_styles();
            for (size_t i = 0; i < styles.size(); ++i) {
                if (i) out += ',';
                out += '"' + json_escape(styles[i].dict_name) + "\":\"" + json_escape(styles[i].styles) + '"';
            }
        } catch (...) {
            LOGE("get_styles threw");
        }
    }
    out += '}';
    return utf8_to_jstring(env, out);
}

JNIEXPORT jbyteArray JNICALL Java_com_yomidroid_dictionary_HoshiDicts_nativeGetMediaFile(
    JNIEnv* env, jobject, jstring jdict, jstring jpath) {
    const std::string dict = jstring_to_utf8(env, jdict);
    const std::string path = jstring_to_utf8(env, jpath);
    std::shared_lock lock(g_mutex);
    if (!g_query) return nullptr;
    std::vector<char> bytes;
    try {
        bytes = g_query->get_media_file(dict, path);
    } catch (...) {
        return nullptr;
    }
    if (bytes.empty()) return nullptr;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
    return arr;
}

}  // extern "C"
