#include "grammar_sampler.h"
#include "prompt_budget.h"
#include "chat_prompt.h"
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <chrono>
#include <atomic>
#include <algorithm>
#include <android/log.h>
#include <dlfcn.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *g_jvm = nullptr;
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static const llama_vocab *g_vocab = nullptr;
static llama_sampler *g_sampler = nullptr;
static GrammarSampler g_grammar;
static common_chat_templates_ptr g_chat_templates;
static std::atomic<int64_t> g_abort_deadline_ms{0};
// Raised from another thread when the reader cancels the page (#76); only the Kotlin side clears it.
static std::atomic<bool> g_abort_requested{false};

// Why an empty reply was empty, read back by nativeLastStatus. The JNI used to collapse a refused
// prompt, an aborted decode and a genuinely empty completion into "", which the batch path cannot
// tell apart: it must fall back to per-line on an overflow and only on an overflow (#149). ENDED
// marks a reply the model closed itself, so a last line without a newline is whole (#368).
enum GenerationStatus {
    GENERATION_OK = 0,
    GENERATION_OVERFLOW = 1,
    GENERATION_TIMEOUT = 2,
    GENERATION_ENDED = 3
};
static std::atomic<int> g_last_status{GENERATION_OK};

static const int64_t DEFAULT_TIMEOUT_MS = 1000;

// Generated text leaves as raw bytes, decoded on the Kotlin side: token pieces can stop mid-character
// or be invalid UTF-8, and NewStringUTF aborts the process on either (#235).
static jbyteArray to_java_bytes(JNIEnv *env, const std::string &bytes) {
    jbyteArray array = env->NewByteArray((jsize)bytes.size());
    if (array != nullptr) {
        env->SetByteArrayRegion(array, 0, (jsize)bytes.size(), (const jbyte *)bytes.data());
    }
    return array;
}

// False when the listener threw (#332); its exception stays pending, so nativeGenerate throws it.
static bool emit_partial(JNIEnv *env, jobject listener, jmethodID invoke, const char *piece, int n) {
    jbyteArray chunk = to_java_bytes(env, std::string(piece, n));
    if (chunk == nullptr) return false;
    jobject unit = env->CallObjectMethod(listener, invoke, chunk);
    env->DeleteLocalRef(chunk);
    if (unit != nullptr) env->DeleteLocalRef(unit);
    return !env->ExceptionCheck();
}

static int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

static bool abort_if_timed_out(void *) {
    if (g_abort_requested.load(std::memory_order_relaxed)) return true;
    const int64_t deadline_ms = g_abort_deadline_ms.load(std::memory_order_relaxed);
    return deadline_ms > 0 && now_ms() > deadline_ms;
}

static bool is_past_deadline() {
    const int64_t deadline_ms = g_abort_deadline_ms.load(std::memory_order_relaxed);
    return deadline_ms > 0 && now_ms() > deadline_ms;
}

// Index order of the sampler parameter array handed over the JNI. Mirrors
// GenerationParams.SAMPLER_INDEX in core/src/main/java/com/yomu/core/TranslationSlot.kt — the two
// lists must be edited together. Every value lives in Kotlin; nothing here is a constant.
enum SamplerIndex {
    SAMPLER_TEMPERATURE = 0,
    SAMPLER_TOP_K,
    SAMPLER_TOP_P,
    SAMPLER_PENALTY_LAST_N,
    SAMPLER_PENALTY_REPEAT,
    SAMPLER_PENALTY_FREQ,
    SAMPLER_PENALTY_PRESENT,
    SAMPLER_PARAM_COUNT
};

static bool rebuild_sampler(const float *params, uint32_t seed) {
    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    auto sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    if (!g_sampler) {
        return false;
    }

    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(g_vocab),
        (int32_t)params[SAMPLER_PENALTY_LAST_N],
        params[SAMPLER_PENALTY_REPEAT],
        params[SAMPLER_PENALTY_FREQ],
        params[SAMPLER_PENALTY_PRESENT]));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k((int32_t)params[SAMPLER_TOP_K]));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(params[SAMPLER_TOP_P], 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(params[SAMPLER_TEMPERATURE]));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(seed));
    return true;
}

// Format the prompt the way the loaded model was trained to receive it.
//
// This used to be a bare English instruction glued in front of the Japanese with no role markers
// at all. The model never saw the turn structure it expects, so it stayed in raw-completion mode:
// it continued the text instead of answering, never emitted EOS, and ran to the token cap on
// nearly every line. That produced both the repetition ("I'm sorry, I'm sorry, ...") and roughly
// four times the necessary latency, since the cost is per generated token.
//
// Preference order: the template embedded in the GGUF, rendered by llama.cpp's Jinja engine, so a
// swapped model brings its own format (ADR-0001 permits user-supplied models); then CAT's format for
// models that ship no template or one the engine cannot parse. llama_chat_apply_template's heuristic
// guesser is gone (#281): it mistook CAT's template for ChatGLM4's and cannot format newer models.
static common_chat_templates_ptr load_chat_templates() {
    // An empty template counts as missing: common_chat_templates_init would swap it for ChatML.
    const char *tmpl = llama_model_chat_template(g_model, nullptr);
    if (!tmpl || !*tmpl) {
        LOGW("Model ships no chat template; using CAT fallback");
        return nullptr;
    }
    try {
        return common_chat_templates_init(g_model, chat_template_override(tmpl));
    } catch (const std::exception &e) {
        LOGW("Model chat template not parseable (%s); using CAT fallback", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    // The CPU backend variants sit beside this library in the app's extracted native lib dir.
    Dl_info info;
    if (dladdr(reinterpret_cast<void *>(&JNI_OnLoad), &info) && info.dli_fname) {
        const std::string lib_path(info.dli_fname);
        ggml_backend_load_all_from_path(lib_path.substr(0, lib_path.rfind('/')).c_str());
    }
    if (ggml_backend_reg_count() == 0) {
        LOGE("No ggml backend loaded; are native libs extracted?");
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yomu_ml_LlamaBridge_nativeLoadModel(
    JNIEnv *env,
    jobject /* this */,
    jstring model_path,
    jint n_ctx,
    jint n_gpu_layers,
    jint n_threads,
    jboolean repack_weights) {

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = n_gpu_layers;
    model_params.use_extra_bufts = repack_weights == JNI_TRUE;

    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    const int threads = std::max(n_threads, 1);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = (uint32_t)n_ctx;
    // Equal to N_CTX today: prompt_fits carries two ceilings (tokens <= batch and
    // tokens <= context - output - 8) and 512 made the first four times tighter than the second,
    // which is the self-refusal #136 measured. 512 was Yomu's line, not upstream's default. Kept a
    // literal rather than derived from n_ctx because the #149 peak-PSS gate may halve it alone.
    ctx_params.n_batch = 2048;
    ctx_params.n_threads = threads;
    ctx_params.n_threads_batch = threads;
    ctx_params.abort_callback = abort_if_timed_out;
    ctx_params.abort_callback_data = nullptr;

    g_ctx = llama_init_from_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    g_chat_templates = load_chat_templates();

    LOGI("Model loaded: n_ctx=%d n_threads=%d n_gpu_layers=%d repack=%d", n_ctx, threads, n_gpu_layers, repack_weights == JNI_TRUE);
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_yomu_ml_LlamaBridge_nativeGenerate(
    JNIEnv *env,
    jobject /* this */,
    jstring prompt,
    jint max_tokens,
    jint timeout_ms,
    jfloatArray sampler_params,
    jint seed,
    jstring grammar,
    jstring system_prompt,
    jobject on_partial) {

    const int64_t started_ms = now_ms();
    g_last_status.store(GENERATION_OK, std::memory_order_relaxed);

    if (!g_ctx || !g_model || !g_vocab) {
        LOGE("Model not loaded");
        return to_java_bytes(env, "");
    }

    // The listener is a Kotlin (ByteArray) -> Unit, so it answers to Function1's erased invoke.
    jmethodID invoke_partial = nullptr;
    if (on_partial) {
        jclass listener_class = env->GetObjectClass(on_partial);
        invoke_partial = env->GetMethodID(listener_class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
        env->DeleteLocalRef(listener_class);
        if (!invoke_partial) return nullptr;
    }

    auto *memory = llama_get_memory(g_ctx);
    if (memory) {
        llama_memory_clear(memory, false);
    }

    const int64_t effective_timeout = timeout_ms > 0 ? (int64_t)timeout_ms : DEFAULT_TIMEOUT_MS;
    g_abort_deadline_ms.store(now_ms() + effective_timeout, std::memory_order_relaxed);
    llama_set_abort_callback(g_ctx, abort_if_timed_out, nullptr);

    if (env->GetArrayLength(sampler_params) < SAMPLER_PARAM_COUNT) {
        LOGE("Sampler parameter array too short: %d", (int)env->GetArrayLength(sampler_params));
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        return to_java_bytes(env, "");
    }

    jfloat *params = env->GetFloatArrayElements(sampler_params, nullptr);
    const bool sampler_ok = params && rebuild_sampler(params, (uint32_t)seed);
    if (params) {
        env->ReleaseFloatArrayElements(sampler_params, params, JNI_ABORT);
    }
    if (!sampler_ok) {
        LOGE("Failed to rebuild sampler");
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        return to_java_bytes(env, "");
    }

    bool grammar_ok;
    if (grammar) {
        const char *grammar_str = env->GetStringUTFChars(grammar, nullptr);
        grammar_ok = g_grammar.rebuild(g_vocab, grammar_str);
        env->ReleaseStringUTFChars(grammar, grammar_str);
    } else {
        g_grammar.reset();
        grammar_ok = true;
    }
    if (!grammar_ok) {
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        return to_java_bytes(env, "");
    }

    const char *system_str = system_prompt ? env->GetStringUTFChars(system_prompt, nullptr) : nullptr;
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string formatted = format_chat_prompt(g_chat_templates.get(), system_str ? system_str : "", prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);
    if (system_str) env->ReleaseStringUTFChars(system_prompt, system_str);

    int prompt_len = (int)formatted.size();

    // parse_special must be true: the formatted prompt carries role markers like <|user|> and
    // </s>, and with it false they tokenize as literal punctuation instead of the control tokens
    // the model was trained on, which defeats the templating entirely.
    int n_tokens = llama_tokenize(g_vocab, formatted.c_str(), prompt_len, nullptr, 0, true, true);
    if (n_tokens < 0) {
        n_tokens = -n_tokens;
    }
    if (n_tokens == 0) {
        LOGE("Failed to tokenize prompt");
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        g_grammar.reset();
        return to_java_bytes(env, "");
    }

    std::vector<llama_token> tokens(n_tokens);
    int written = llama_tokenize(g_vocab, formatted.c_str(), prompt_len, tokens.data(), n_tokens, true, true);
    if (written < 0) {
        LOGE("Failed to write prompt tokens");
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        g_grammar.reset();
        return to_java_bytes(env, "");
    }
    tokens.resize(written);

    if (!prompt_fits((int)tokens.size(), (int)llama_n_batch(g_ctx), (int)llama_n_ctx(g_ctx), max_tokens)) {
        LOGE("Prompt exceeds decode budget: %d tokens; refusing to truncate instructions", (int)tokens.size());
        g_last_status.store(GENERATION_OVERFLOW, std::memory_order_relaxed);
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        g_grammar.reset();
        return to_java_bytes(env, "");
    }

    // Process prompt
    llama_batch batch = llama_batch_get_one(tokens.data(), (int)tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        const int64_t elapsed_ms = now_ms() - started_ms;
        if (is_past_deadline()) {
            LOGW("Decode prompt aborted deadline elapsedMs=%lld", (long long)elapsed_ms);
            g_last_status.store(GENERATION_TIMEOUT, std::memory_order_relaxed);
        } else {
            LOGE("Failed to decode prompt elapsedMs=%lld", (long long)elapsed_ms);
        }
        g_abort_deadline_ms.store(0, std::memory_order_relaxed);
        g_grammar.reset();
        return to_java_bytes(env, "");
    }

    // Generation loop
    std::string result;
    bool listener_threw = false;
    int n_len = 0;
    llama_token new_token_id;
    const int n_vocab = llama_vocab_n_tokens(g_vocab);
    std::vector<llama_token_data> cur;

    while (n_len < max_tokens) {
        if (g_abort_requested.load(std::memory_order_relaxed)) {
            LOGI("Generation cancelled generatedTokens=%d", n_len);
            break;
        }
        new_token_id = g_grammar.sample(g_ctx, g_sampler, cur, n_vocab);

        if (llama_vocab_is_eog(g_vocab, new_token_id)) {
            g_last_status.store(GENERATION_ENDED, std::memory_order_relaxed);
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(g_vocab, new_token_id, buf, sizeof(buf), 0, false);
        if (n > 0) {
            result.append(buf, n);
            if (on_partial && !emit_partial(env, on_partial, invoke_partial, buf, n)) {
                listener_threw = true;
                break;
            }
        }

        // Feed token back
        tokens[0] = new_token_id;
        batch = llama_batch_get_one(tokens.data(), 1);
        if (llama_decode(g_ctx, batch) != 0) {
            const int64_t elapsed_ms = now_ms() - started_ms;
            if (is_past_deadline()) {
                LOGW("Decode token aborted deadline generatedTokens=%d elapsedMs=%lld", n_len, (long long)elapsed_ms);
                g_last_status.store(GENERATION_TIMEOUT, std::memory_order_relaxed);
            } else {
                LOGE("Failed to decode generated token at=%d elapsedMs=%lld", n_len, (long long)elapsed_ms);
            }
            break;
        }

        n_len++;
    }

    const int64_t elapsed_ms = now_ms() - started_ms;
    LOGI("Generation complete tokens=%d resultLength=%zu durationMs=%lld grammarRejections=%d grammar=%d",
         n_len, result.size(), (long long)elapsed_ms, g_grammar.rejections(), g_grammar.active() ? 1 : 0);
    g_grammar.reset();
    g_abort_deadline_ms.store(0, std::memory_order_relaxed);
    if (listener_threw) {
        LOGE("Partial-output listener threw generatedTokens=%d", n_len);
        return nullptr;
    }
    return to_java_bytes(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yomu_ml_LlamaBridge_nativeSetAbortRequested(JNIEnv *, jobject /* this */, jboolean requested) {
    g_abort_requested.store(requested == JNI_TRUE, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_yomu_ml_LlamaBridge_nativeLastStatus(JNIEnv *, jobject /* this */) {
    return (jint)g_last_status.load(std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_yomu_ml_LlamaBridge_nativeClearMemory(JNIEnv *, jobject /* this */) {
    auto *memory = llama_get_memory(g_ctx);
    if (memory) {
        llama_memory_clear(memory, false);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_yomu_ml_LlamaBridge_nativeRelease(JNIEnv *env, jobject /* this */) {
    LOGI("Releasing model resources");

    g_grammar.reset();
    g_chat_templates.reset();

    if (g_sampler) {
        llama_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }

    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    g_vocab = nullptr;
    llama_backend_free();
    LOGI("Model resources released");
}
