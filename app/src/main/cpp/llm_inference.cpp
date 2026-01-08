#include <android/log.h>
#include <jni.h>
#include <string>
#include <unistd.h>

#include "common.h"
#include "sampling.h"
#include "llama.h"

#define LOG_TAG "YomidroidLLM"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Configuration
constexpr int N_THREADS_MIN = 2;
constexpr int N_THREADS_MAX = 4;
constexpr int N_THREADS_HEADROOM = 2;
constexpr int DEFAULT_CONTEXT_SIZE = 2048;
constexpr int BATCH_SIZE = 512;
constexpr float DEFAULT_TEMP = 0.3f;

// Global state
static llama_model *g_model = nullptr;
static llama_context *g_context = nullptr;
static llama_batch g_batch;
static common_sampler *g_sampler = nullptr;
static bool g_initialized = false;

extern "C" JNIEXPORT void JNICALL
Java_com_yomidroid_llm_LlamaCpp_init(JNIEnv *env, jobject) {
    if (g_initialized) return;

    llama_log_set([](ggml_log_level level, const char *text, void *) {
        switch (level) {
            case GGML_LOG_LEVEL_ERROR: LOGE("%s", text); break;
            case GGML_LOG_LEVEL_WARN: LOGI("%s", text); break;
            default: LOGD("%s", text); break;
        }
    }, nullptr);

    llama_backend_init();
    g_initialized = true;
    LOGI("LLM backend initialized");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yomidroid_llm_LlamaCpp_loadModel(JNIEnv *env, jobject, jstring jmodelPath) {
    if (g_model) {
        LOGE("Model already loaded");
        return JNI_FALSE;
    }

    const char *modelPath = env->GetStringUTFChars(jmodelPath, nullptr);
    LOGI("Loading model from: %s", modelPath);

    llama_model_params modelParams = llama_model_default_params();
    g_model = llama_model_load_from_file(modelPath, modelParams);
    env->ReleaseStringUTFChars(jmodelPath, modelPath);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Initialize context
    const int nThreads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
        (int)sysconf(_SC_NPROCESSORS_ONLN) - N_THREADS_HEADROOM));
    LOGI("Using %d threads", nThreads);

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = DEFAULT_CONTEXT_SIZE;
    ctxParams.n_batch = BATCH_SIZE;
    ctxParams.n_ubatch = BATCH_SIZE;
    ctxParams.n_threads = nThreads;
    ctxParams.n_threads_batch = nThreads;

    g_context = llama_init_from_model(g_model, ctxParams);
    if (!g_context) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);

    common_params_sampling samplingParams;
    samplingParams.temp = DEFAULT_TEMP;
    g_sampler = common_sampler_init(g_model, samplingParams);

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yomidroid_llm_LlamaCpp_generate(JNIEnv *env, jobject, jstring jprompt, jint maxTokens) {
    if (!g_model || !g_context) {
        LOGE("Model not loaded");
        return env->NewStringUTF("");
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    LOGD("Generating with prompt: %.100s...", prompt);

    // Clear KV cache
    llama_memory_clear(llama_get_memory(g_context), false);
    common_sampler_reset(g_sampler);

    // Tokenize prompt
    std::vector<llama_token> tokens = common_tokenize(g_context, prompt, true, true);
    env->ReleaseStringUTFChars(jprompt, prompt);

    if (tokens.empty()) {
        LOGE("Tokenization failed");
        return env->NewStringUTF("");
    }

    LOGD("Tokenized %d tokens", (int)tokens.size());

    // Decode prompt in batches
    for (size_t i = 0; i < tokens.size(); i += BATCH_SIZE) {
        const int batchSize = std::min((int)(tokens.size() - i), BATCH_SIZE);
        common_batch_clear(g_batch);

        for (int j = 0; j < batchSize; j++) {
            const bool isLast = (i + j == tokens.size() - 1);
            common_batch_add(g_batch, tokens[i + j], i + j, {0}, isLast);
        }

        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("Decode failed during prompt processing");
            return env->NewStringUTF("");
        }
    }

    // Generate tokens
    std::string result;
    llama_pos pos = tokens.size();
    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    for (int i = 0; i < maxTokens; i++) {
        llama_token newToken = common_sampler_sample(g_sampler, g_context, -1);
        common_sampler_accept(g_sampler, newToken, true);

        if (llama_vocab_is_eog(vocab, newToken)) {
            LOGD("End of generation at token %d", i);
            break;
        }

        std::string piece = common_token_to_piece(g_context, newToken);
        result += piece;

        common_batch_clear(g_batch);
        common_batch_add(g_batch, newToken, pos++, {0}, true);

        if (llama_decode(g_context, g_batch) != 0) {
            LOGE("Decode failed during generation");
            break;
        }
    }

    LOGD("Generated %d characters", (int)result.size());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_yomidroid_llm_LlamaCpp_unloadModel(JNIEnv *, jobject) {
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    llama_batch_free(g_batch);
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGI("Model unloaded");
}

extern "C" JNIEXPORT void JNICALL
Java_com_yomidroid_llm_LlamaCpp_shutdown(JNIEnv *, jobject) {
    Java_com_yomidroid_llm_LlamaCpp_unloadModel(nullptr, nullptr);
    llama_backend_free();
    g_initialized = false;
    LOGI("LLM backend shutdown");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yomidroid_llm_LlamaCpp_isModelLoaded(JNIEnv *, jobject) {
    return g_model != nullptr && g_context != nullptr ? JNI_TRUE : JNI_FALSE;
}
