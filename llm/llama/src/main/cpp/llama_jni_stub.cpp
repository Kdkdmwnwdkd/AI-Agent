#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <climits>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

#if defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP
#include "chat.h"
#include "llama.h"
#include "nlohmann/json.hpp"
#include <cstdlib>
#include <ctime>
#include <algorithm>
#include <exception>
#include <memory>
#include <sstream>

struct ToolCallGrammarConfigNative {
    std::string grammar;
    bool lazy = false;
    std::vector<std::string> triggerPatterns;
    std::vector<llama_token> triggerTokens;
    std::string generationPrompt;
};
#endif

#define TAG "LlamaNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * jstring → std::string。
 *
 * GetStringUTFChars 在内存不足时会返回 NULL **并挂起一个 OutOfMemoryError**。
 * 此时若直接 `std::string(cstr)`，libc++ 会在 strlen(nullptr) 上 SIGSEGV ——
 * 这个崩溃发生在 native 层，Kotlin 侧拦不住。因此必须判空并安全降级为空串。
 */
static std::string jstringToString(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char * cstr = env->GetStringUTFChars(jstr, nullptr);
    if (cstr == nullptr) {
        LOGE("GetStringUTFChars returned null (OOM); degrading to empty string");
        return "";
    }
    std::string out(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return out;
}

/**
 * JNI 异常屏障。
 *
 * 背景（2026-10-03 闪退根因，魅族20 / Adreno 740）：
 * llama.cpp 的 Vulkan 后端用 Vulkan-Hpp（vk:: 命名空间），该绑定默认启用 C++ 异常。
 * 当设备驱动不支持某个 compute pipeline 时，ggml-vulkan 会抛出
 *   vk::SystemError: vk::Device::createComputePipeline: ErrorUnknown
 * 这个 C++ 异常会一路穿过 libggml-vulkan.so → libllama.so → libLlamaWrapper.so，
 * 若在 JNI 边界逃逸出去，C++ 运行时直接调用 std::terminate() → SIGABRT 杀进程，
 * 表现为"发消息没回复 + 突然闪退"，且 Java 层拿不到任何堆栈。
 *
 * 因此所有 JNI 入口都必须经过这道屏障：把 C++ 异常转成 Java 异常抛回上层，
 * 让 Kotlin 侧的 Result/try-catch 能够正常处理，而不是整进程崩溃。
 *
 * @param env    JNI 环境，用于抛出 Java 异常（可为空，为空时仅记录日志）
 * @param label  上下文标识，写入日志便于定位是哪个入口出的问题
 * @param fallback 出错时的返回值
 */
template <typename Fn, typename Fallback>
static auto jniExceptionBarrier(JNIEnv * env, const char * label, Fallback fallback, Fn && body) -> decltype(body()) {
    try {
        return body();
    } catch (const std::exception & e) {
        // Vulkan 后端失败（如 vk::SystemError）会走到这里，而不是让进程 abort。
        LOGE("JNI %s threw C++ exception: %s", label, e.what());
        if (env != nullptr && !env->ExceptionCheck()) {
            jclass cls = env->FindClass("java/lang/IllegalStateException");
            if (cls != nullptr) {
                env->ThrowNew(cls, e.what());
                env->DeleteLocalRef(cls);
            }
        }
        return fallback;
    } catch (...) {
        LOGE("JNI %s threw unknown C++ exception", label);
        if (env != nullptr && !env->ExceptionCheck()) {
            jclass cls = env->FindClass("java/lang/IllegalStateException");
            if (cls != nullptr) {
                env->ThrowNew(cls, "Unknown native error in llama.cpp backend");
                env->DeleteLocalRef(cls);
            }
        }
        return fallback;
    }
}

#if defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP
static llama_sampler * createSamplerChain(
        const llama_vocab * vocab,
        float temperature,
        float topP,
        int32_t topK,
        int32_t penaltyLastN,
        float repeatPenalty,
        float frequencyPenalty,
        float presencePenalty,
        uint32_t seed,
        const ToolCallGrammarConfigNative * grammarConfig
) {
    if (topP < 0.0f) topP = 0.0f;
    if (topP > 1.0f) topP = 1.0f;
    if (topK < 0) topK = 0;
    if (penaltyLastN < -1) penaltyLastN = -1;
    if (repeatPenalty < 0.0f) repeatPenalty = 0.0f;

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * chain = llama_sampler_chain_init(sparams);
    if (!chain) return nullptr;

    llama_sampler_chain_add(chain, llama_sampler_init_penalties(
            penaltyLastN,
            repeatPenalty,
            frequencyPenalty,
            presencePenalty
    ));

    llama_sampler_chain_add(chain, llama_sampler_init_top_k(topK));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(topP, 1));
    llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));

    if (grammarConfig != nullptr && !grammarConfig->grammar.empty()) {
        if (vocab == nullptr) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler * grammarSampler = nullptr;
        if (grammarConfig->lazy) {
            std::vector<const char *> triggerPatternsC;
            triggerPatternsC.reserve(grammarConfig->triggerPatterns.size());
            for (const auto & pattern : grammarConfig->triggerPatterns) {
                if (!pattern.empty()) {
                    triggerPatternsC.push_back(pattern.c_str());
                }
            }

            grammarSampler = llama_sampler_init_grammar_lazy_patterns(
                vocab,
                grammarConfig->grammar.c_str(),
                "root",
                triggerPatternsC.data(),
                triggerPatternsC.size(),
                grammarConfig->triggerTokens.data(),
                grammarConfig->triggerTokens.size()
            );
        } else {
            grammarSampler = llama_sampler_init_grammar(
                vocab,
                grammarConfig->grammar.c_str(),
                "root"
            );
        }

        if (!grammarSampler) {
            llama_sampler_free(chain);
            return nullptr;
        }

        llama_sampler_chain_add(chain, grammarSampler);
    }

    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));

    return chain;
}
#endif

static jstring stringToJstring(JNIEnv * env, const std::string & str) {
    return env->NewStringUTF(str.c_str());
}

static jstring bytesUtf8ToJstring(JNIEnv * env, const std::string & bytes) {
    std::u16string out;
    out.reserve(bytes.size());

    const unsigned char * s = reinterpret_cast<const unsigned char *>(bytes.data());
    size_t i = 0;
    while (i < bytes.size()) {
        uint32_t cp = 0;
        const unsigned char c0 = s[i];

        if (c0 < 0x80) {
            cp = c0;
            i += 1;
        } else if ((c0 & 0xE0) == 0xC0 && i + 1 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            if ((c1 & 0xC0) != 0x80) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x1F) << 6) | (c1 & 0x3F);
                if (cp < 0x80) cp = 0xFFFD;
                i += 2;
            }
        } else if ((c0 & 0xF0) == 0xE0 && i + 2 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];
            if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80)) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x0F) << 12) | ((c1 & 0x3F) << 6) | (c2 & 0x3F);
                if (cp < 0x800) cp = 0xFFFD;
                i += 3;
            }
        } else if ((c0 & 0xF8) == 0xF0 && i + 3 < bytes.size()) {
            const unsigned char c1 = s[i + 1];
            const unsigned char c2 = s[i + 2];
            const unsigned char c3 = s[i + 3];
            if (((c1 & 0xC0) != 0x80) || ((c2 & 0xC0) != 0x80) || ((c3 & 0xC0) != 0x80)) {
                cp = 0xFFFD;
                i += 1;
            } else {
                cp = ((c0 & 0x07) << 18) | ((c1 & 0x3F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                if (cp < 0x10000 || cp > 0x10FFFF) cp = 0xFFFD;
                i += 4;
            }
        } else {
            cp = 0xFFFD;
            i += 1;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        }
    }

    return env->NewString(reinterpret_cast<const jchar *>(out.data()), static_cast<jsize>(out.size()));
}

#if !(defined(OPERIT_HAS_LLAMA_CPP) && OPERIT_HAS_LLAMA_CPP)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable(JNIEnv * env, jclass clazz) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason(JNIEnv * env, jclass clazz) {
    const char * msg = "llama.cpp native backend is not built. Ensure CMake fetched llama.cpp and links target 'llama'.";
    return env->NewStringUTF(msg);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
        JNIEnv * env,
        jclass clazz,
        jstring pathModel,
        jint nThreads,
        jint nCtx,
        jint nBatch,
        jint nUBatch,
        jint nGpuLayers,
        jboolean useMmap,
        jboolean flashAttention,
        jboolean kvUnified,
        jboolean offloadKqv
) {
    (void) env;
    (void) clazz;
    (void) pathModel;
    (void) nThreads;
    (void) nCtx;
    (void) nBatch;
    (void) nUBatch;
    (void) nGpuLayers;
    (void) useMmap;
    (void) flashAttention;
    (void) kvUnified;
    (void) offloadKqv;
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCancel(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring text) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) text;
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repetitionPenalty,
        jfloat frequencyPenalty,
        jfloat presencePenalty,
        jint penaltyLastN
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) temperature;
    (void) topP;
    (void) topK;
    (void) repetitionPenalty;
    (void) frequencyPenalty;
    (void) presencePenalty;
    (void) penaltyLastN;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jobjectArray roles,
        jobjectArray contents,
        jboolean enableThinking,
        jboolean addAssistant
) {
    (void) clazz;
    (void) sessionPtr;
    (void) roles;
    (void) contents;
    (void) enableThinking;
    (void) addAssistant;
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring messagesJson,
        jstring toolsJson,
        jboolean enableThinking,
        jboolean addAssistant
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) messagesJson;
    (void) toolsJson;
    (void) enableThinking;
    (void) addAssistant;
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring prompt, jint maxTokens, jobject callback) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) prompt;
    (void) maxTokens;
    (void) callback;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring grammar,
        jobjectArray triggerPatterns
) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) grammar;
    (void) triggerPatterns;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring content) {
    (void) env;
    (void) clazz;
    (void) sessionPtr;
    (void) content;
    return nullptr;
}

#else

namespace {

struct SamplingParamsNative {
    float temperature = 1.0f;
    float topP = 1.0f;
    int32_t topK = 0;
    int32_t penaltyLastN = 64;
    float repeatPenalty = 1.0f;
    float frequencyPenalty = 0.0f;
    float presencePenalty = 0.0f;
    uint32_t seed = static_cast<uint32_t>(std::rand());
};

struct LlamaSessionNative {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    common_chat_templates_ptr chatTemplates;
    SamplingParamsNative samplingParams;
    ToolCallGrammarConfigNative toolCallGrammar;
    common_chat_parser_params toolCallParserParams;
    bool hasToolCallParser = false;
    std::atomic_bool cancel{false};
};

static std::once_flag gBackendInitOnce;

static void ensureBackendInit() {
    std::call_once(gBackendInitOnce, []() {
        llama_backend_init();
        std::srand(static_cast<unsigned int>(std::time(nullptr)));
        LOGI("llama_backend_init done");
    });
}

/**
 * 存活会话集合 —— 句柄生命周期防线。
 *
 * ## 为什么需要它（真实可达的 UAF，不是理论风险）
 *
 * Kotlin 侧 [LlamaSession] 的 `release()` 自带 `released` 标志防重复释放，
 * 但它的 `generateStream()` 是**在锁外**拿着 ptr 调用 native 的（见 LlamaSession.kt），
 * 因为推理可能跑几十秒，不能一直持锁。于是存在这条时序：
 *
 *   T1(推理线程)  LlamaSession.generateStream → 进入 nativeGenerateStream（长任务）
 *   T2(UI 线程)   用户点「停止」/切走页面 → LlamaProvider.release() → LlamaSession.release()
 *                 → nativeReleaseSession → delete session
 *   T1            native 仍在用 session->ctx / session->sampler → **UAF → SIGSEGV**
 *
 * 单纯的 `sessionPtr == 0` 判断拦不住：T1 手里那个 ptr 已经变成野指针，非 0。
 *
 * ## 做法
 *
 * 维护一张「当前存活指针」表。所有 JNI 入口先从表里取指针，
 * 取不到就说明会话已释放，直接安全返回；`nativeReleaseSession` 先摘表再 delete。
 * 用 `std::mutex` 保护，开销远小于一次推理。
 *
 * 注意：这只能防止「访问已释放内存」，不能保证正在执行的推理安全结束 ——
 * 那需要 join 推理线程，属于更重的改造。当前策略是让 release 期间的并发访问
 * 安全失败（返回错误），而不是崩溃。
 */
static std::mutex gLiveSessionsMutex;
static std::unordered_set<LlamaSessionNative *> gLiveSessions;

static LlamaSessionNative * acquireLiveSession(jlong sessionPtr) {
    if (sessionPtr == 0) return nullptr;
    auto * session = reinterpret_cast<LlamaSessionNative *>(sessionPtr);
    std::lock_guard<std::mutex> lock(gLiveSessionsMutex);
    return gLiveSessions.count(session) > 0 ? session : nullptr;
}

static void publishLiveSession(LlamaSessionNative * session) {
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lock(gLiveSessionsMutex);
    gLiveSessions.insert(session);
}

static bool retireLiveSession(LlamaSessionNative * session) {
    if (session == nullptr) return false;
    std::lock_guard<std::mutex> lock(gLiveSessionsMutex);
    return gLiveSessions.erase(session) > 0;
}

static uint32_t positiveOrDefaultUInt(jint value, uint32_t defaultValue) {
    return value > 0 ? static_cast<uint32_t>(value) : defaultValue;
}

static int32_t positiveOrDefaultInt(jint value, int32_t defaultValue) {
    return value > 0 ? static_cast<int32_t>(value) : defaultValue;
}

static bool jbooleanToBool(jboolean value) {
    return value == JNI_TRUE;
}

static bool abortCallback(void * user_data) {
    auto * session = reinterpret_cast<LlamaSessionNative *>(user_data);
    return session != nullptr && session->cancel.load();
}

static bool rebuildSamplerForSession(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr || session->ctx == nullptr) {
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    llama_sampler * next = createSamplerChain(
        vocab,
        session->samplingParams.temperature,
        session->samplingParams.topP,
        session->samplingParams.topK,
        session->samplingParams.penaltyLastN,
        session->samplingParams.repeatPenalty,
        session->samplingParams.frequencyPenalty,
        session->samplingParams.presencePenalty,
        session->samplingParams.seed,
        &session->toolCallGrammar
    );

    if (!next) {
        return false;
    }

    if (session->sampler) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    session->sampler = next;
    return true;
}

static int32_t tokenizeText(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    if (vocab == nullptr) return 0;
    // text.size() 转 int32_t 会溢出（>2G 的畸形输入），溢出后 capacity 变负、
    // llama_tokenize 收到负的文本长度，行为未定义。直接拒绝超长输入。
    if (text.size() > static_cast<size_t>(INT32_MAX - 8)) {
        LOGE("tokenizeText: input too long (%zu bytes)", text.size());
        return 0;
    }
    const int32_t textLen = static_cast<int32_t>(text.size());
    int32_t capacity = textLen + 8;
    std::vector<llama_token> tokens;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        textLen,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    return std::max<int32_t>(0, n);
}

static std::vector<llama_token> tokenizeTextToVector(const llama_vocab * vocab, const std::string & text, bool addSpecial) {
    std::vector<llama_token> tokens;
    if (vocab == nullptr || text.empty()) {
        return tokens;
    }
    // 同 tokenizeText：拒绝会令 int32_t 长度溢出的超长输入。
    if (text.size() > static_cast<size_t>(INT32_MAX - 8)) {
        LOGE("tokenizeTextToVector: input too long (%zu bytes)", text.size());
        return tokens;
    }

    const int32_t textLen = static_cast<int32_t>(text.size());
    int32_t capacity = textLen + 8;
    tokens.resize(std::max(16, capacity));

    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        textLen,
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        addSpecial,
        true
    );

    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            textLen,
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            addSpecial,
            true
        );
    }

    if (n <= 0) {
        tokens.clear();
        return tokens;
    }

    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

static ToolCallGrammarConfigNative buildToolCallGrammarConfig(const common_chat_params & params) {
    ToolCallGrammarConfigNative config;
    config.grammar = params.grammar;
    config.lazy = params.grammar_lazy;
    config.generationPrompt = params.generation_prompt;

    for (const auto & trigger : params.grammar_triggers) {
        switch (trigger.type) {
            case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                config.triggerPatterns.push_back(regex_escape(trigger.value));
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                config.triggerPatterns.push_back(trigger.value);
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                if (trigger.value.empty()) {
                    config.triggerPatterns.push_back("^$");
                } else {
                    std::string anchored;
                    if (trigger.value.front() != '^') {
                        anchored.push_back('^');
                    }
                    anchored += trigger.value;
                    if (trigger.value.back() != '$') {
                        anchored.push_back('$');
                    }
                    config.triggerPatterns.push_back(std::move(anchored));
                }
                break;
            case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                if (trigger.token != LLAMA_TOKEN_NULL) {
                    config.triggerTokens.push_back(trigger.token);
                }
                break;
            default:
                break;
        }
    }

    return config;
}

static void resetToolCallState(LlamaSessionNative * session) {
    if (session == nullptr) {
        return;
    }

    session->toolCallGrammar = ToolCallGrammarConfigNative{};
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;
}

static bool initializeChatTemplatesForSession(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr) {
        return false;
    }

    try {
        session->chatTemplates = common_chat_templates_init(session->model, "");
        return static_cast<bool>(session->chatTemplates);
    } catch (const std::exception & e) {
        LOGE("Failed to initialize chat templates: %s", e.what());
        session->chatTemplates.reset();
        return false;
    } catch (...) {
        LOGE("Failed to initialize chat templates: unknown error");
        session->chatTemplates.reset();
        return false;
    }
}

static bool buildChatMessages(
        const std::vector<std::string> & roles,
        const std::vector<std::string> & contents,
        std::vector<common_chat_msg> & outMessages
) {
    if (roles.size() != contents.size()) {
        return false;
    }

    outMessages.clear();
    outMessages.reserve(roles.size());

    for (size_t i = 0; i < roles.size(); ++i) {
        common_chat_msg msg;
        msg.role = roles[i];
        msg.content = contents[i];
        outMessages.push_back(std::move(msg));
    }

    return true;
}

static bool tokenToPiece(const llama_vocab * vocab, llama_token token, std::string & out) {
    if (vocab == nullptr) return false;
    std::vector<char> buf;
    buf.resize(256);

    int32_t n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    if (n < 0) {
        buf.resize(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, buf.data(), static_cast<int32_t>(buf.size()), 0, true);
    }
    if (n <= 0) return false;
    out.assign(buf.data(), buf.data() + n);
    return true;
}

static void prefillToolCallGenerationPrompt(LlamaSessionNative * session) {
    if (session == nullptr || session->model == nullptr || session->sampler == nullptr) {
        return;
    }
    if (session->toolCallGrammar.grammar.empty() || session->toolCallGrammar.generationPrompt.empty()) {
        return;
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    auto tokens = tokenizeTextToVector(vocab, session->toolCallGrammar.generationPrompt, false);
    for (const auto token : tokens) {
        llama_sampler_accept(session->sampler, token);
    }
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeIsAvailable(JNIEnv * env, jclass clazz) {
    (void) env;
    (void) clazz;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGetUnavailableReason(JNIEnv * env, jclass clazz) {
    (void) clazz;
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCreateSession(
        JNIEnv * env,
        jclass clazz,
        jstring pathModel,
        jint nThreads,
        jint nCtx,
        jint nBatch,
        jint nUBatch,
        jint nGpuLayers,
        jboolean useMmap,
        jboolean flashAttention,
        jboolean kvUnified,
        jboolean offloadKqv
) {
    // 崩溃防线之一：Vulkan 后端创建 pipeline 失败会抛 vk::SystemError，
    // 必须在 JNI 边界拦住，否则逃逸出去会 SIGABRT 杀进程（详见 jniExceptionBarrier 注释）。
    return jniExceptionBarrier(env, "nativeCreateSession", static_cast<jlong>(0), [&]() -> jlong {
    (void) clazz;
    ensureBackendInit();

    const std::string modelPath = jstringToString(env, pathModel);
    const int32_t effectiveThreads = positiveOrDefaultInt(nThreads, 4);
    const bool gpuOffloadSupported = llama_supports_gpu_offload();
    const int32_t requestedGpuLayers = std::max<int32_t>(0, static_cast<int32_t>(nGpuLayers));
    const int32_t effectiveGpuLayers = gpuOffloadSupported ? requestedGpuLayers : 0;
    const bool effectiveUseMmap = jbooleanToBool(useMmap);
    const bool effectiveFlashAttention = jbooleanToBool(flashAttention);
    const bool effectiveKvUnified = jbooleanToBool(kvUnified);
    const bool effectiveOffloadKqv =
        gpuOffloadSupported &&
        effectiveGpuLayers > 0 &&
        jbooleanToBool(offloadKqv);

    auto * session = new (std::nothrow) LlamaSessionNative();
    if (!session) {
        LOGE("Failed to allocate session");
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = effectiveGpuLayers;
    // llama.cpp now exposes model memory mapping through load_mode; the old
    // use_mmap/use_mlock fields no longer exist in llama_model_params.
    mparams.load_mode = effectiveUseMmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    mparams.use_extra_bufts = true;

    LOGI(
        "Creating llama session. model=%s threads=%d n_ctx=%d n_batch=%d n_ubatch=%d gpu_layers=%d use_mmap=%d flash_attn=%d kv_unified=%d offload_kqv=%d gpu_support=%d",
        modelPath.c_str(),
        effectiveThreads,
        static_cast<int>(nCtx),
        static_cast<int>(nBatch),
        static_cast<int>(nUBatch),
        effectiveGpuLayers,
        effectiveUseMmap ? 1 : 0,
        effectiveFlashAttention ? 1 : 0,
        effectiveKvUnified ? 1 : 0,
        effectiveOffloadKqv ? 1 : 0,
        gpuOffloadSupported ? 1 : 0
    );

    if (requestedGpuLayers > 0 && !gpuOffloadSupported) {
        LOGI("GPU layers requested but this build has no GPU offload backend; continuing on CPU");
    }

    // 崩溃防线之二：GPU 后端（尤其 Vulkan）在部分驱动上会在加载/建图阶段失败。
    // 典型报错：vk::Device::createComputePipeline: ErrorUnknown（魅族20 / Adreno 740）。
    // 一旦抛出，整个会话就没救了；这里捕获后自动退回纯 CPU 重建，保证本地模型仍可用，
    // 而不是把异常抛给上层导致闪退。
    session->model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!session->model) {
        if (effectiveGpuLayers > 0) {
            LOGW("Model load failed with GPU offload (gpu_layers=%d); retrying on CPU only", effectiveGpuLayers);
            mparams.n_gpu_layers = 0;
            session->model = llama_model_load_from_file(modelPath.c_str(), mparams);
        }
        if (!session->model) {
            LOGE("Failed to load model from file");
            delete session;
            return 0;
        }
        LOGI("Model loaded successfully in CPU-only fallback mode");
    }

    if (!initializeChatTemplatesForSession(session)) {
        LOGE("Failed to initialize chat templates for model");
        llama_model_free(session->model);
        session->model = nullptr;
        delete session;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = positiveOrDefaultUInt(nCtx, 0u);
    if (cparams.n_ctx == 0) {
        cparams.n_ctx = static_cast<uint32_t>(llama_model_n_ctx_train(session->model));
    }
    const uint32_t defaultBatch = std::min<uint32_t>(cparams.n_ctx, 512u);
    cparams.n_batch = std::min<uint32_t>(positiveOrDefaultUInt(nBatch, defaultBatch), cparams.n_ctx);
    cparams.n_ubatch = std::min<uint32_t>(positiveOrDefaultUInt(nUBatch, cparams.n_batch), cparams.n_batch);
    cparams.n_seq_max = 1;
    cparams.n_threads = effectiveThreads;
    cparams.n_threads_batch = effectiveThreads;
    cparams.flash_attn_type =
        effectiveFlashAttention ? LLAMA_FLASH_ATTN_TYPE_ENABLED : LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.offload_kqv = effectiveOffloadKqv;
    cparams.kv_unified = effectiveKvUnified;
    cparams.abort_callback = abortCallback;
    cparams.abort_callback_data = session;

    session->ctx = llama_init_from_model(session->model, cparams);
    if (!session->ctx) {
        LOGE("Failed to create context");
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    llama_set_n_threads(session->ctx, effectiveThreads, effectiveThreads);

    session->samplingParams = SamplingParamsNative{};
    session->samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!rebuildSamplerForSession(session)) {
        LOGE("Failed to create sampler chain");
        llama_free(session->ctx);
        llama_model_free(session->model);
        delete session;
        return 0;
    }

    session->cancel.store(false);

    // 登记到存活集合，之后所有入口都要能在这里查到才允许使用。
    publishLiveSession(session);

    return reinterpret_cast<jlong>(session);
    }); // jniExceptionBarrier(nativeCreateSession)
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeReleaseSession(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;

    // 先摘表再释放：并发中的推理线程若拿到过 ptr，acquireLiveSession 会查不到而安全返回。
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) {
        // 已释放 / 从未登记的句柄，直接忽略（历史上这里是 double-free 的入口）。
        if (sessionPtr != 0) LOGW("nativeReleaseSession: session already released, ignoring");
        return;
    }
    retireLiveSession(session);

    if (session->sampler) {
        llama_sampler_free(session->sampler);
        session->sampler = nullptr;
    }

    if (session->ctx) {
        llama_free(session->ctx);
        session->ctx = nullptr;
    }

    session->chatTemplates.reset();

    if (session->model) {
        llama_model_free(session->model);
        session->model = nullptr;
    }

    delete session;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCancel(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) env;
    (void) clazz;
    // 会话可能已被释放（用户先点了停止又切走页面），查不到就静默忽略而不是写野指针。
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return;
    session->cancel.store(true);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeCountTokens(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring text) {
    (void) clazz;
    return jniExceptionBarrier(env, "nativeCountTokens", static_cast<jint>(0), [&]() -> jint {
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return 0;
    if (!session->model) return 0;
    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    // 模型加载半成功时 vocab 可能为 null，llama_tokenize 对 null vocab 会直接解引用。
    if (vocab == nullptr) {
        LOGE("nativeCountTokens: model vocab is null");
        return 0;
    }
    const std::string input = jstringToString(env, text);
    return static_cast<jint>(tokenizeText(vocab, input, true));
    }); // jniExceptionBarrier(nativeCountTokens)
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetSamplingParams(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repetitionPenalty,
        jfloat frequencyPenalty,
        jfloat presencePenalty,
        jint penaltyLastN
) {
    (void) clazz;

    // 崩溃防线：本函数会调 rebuildSamplerForSession → createSamplerChain，
    // 而 sampler chain 初始化会走 ggml 后端代码路径。Vulkan 后端在此阶段抛
    // vk::SystemError 时，若无屏障会逃出 JNI → std::terminate() → SIGABRT。
    // 这与 nativeCreateSession 是同一条崩溃链，必须一并拦。
    return jniExceptionBarrier(env, "nativeSetSamplingParams", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return JNI_FALSE;
    if (!session->ctx || !session->model) return JNI_FALSE;

    session->samplingParams.temperature = (float) temperature;
    session->samplingParams.topP = (float) topP;
    session->samplingParams.topK = (int32_t) topK;
    session->samplingParams.penaltyLastN = (int32_t) penaltyLastN;
    session->samplingParams.repeatPenalty = (float) repetitionPenalty;
    session->samplingParams.frequencyPenalty = (float) frequencyPenalty;
    session->samplingParams.presencePenalty = (float) presencePenalty;
    session->samplingParams.seed = static_cast<uint32_t>(std::rand());

    if (!rebuildSamplerForSession(session)) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
    }); // jniExceptionBarrier(nativeSetSamplingParams)
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeSetToolCallGrammar(
        JNIEnv * env,
        jclass clazz,
        jlong sessionPtr,
        jstring grammar,
        jobjectArray triggerPatterns
) {
    (void) clazz;

    // 崩溃防线：grammar sampler 初始化需要额外建 ggml 图/pipeline，
    // 是 Vulkan 后端最容易在 createComputePipeline 上抛 vk::SystemError 的入口之一。
    return jniExceptionBarrier(env, "nativeSetToolCallGrammar", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {
    if (grammar == nullptr) return JNI_FALSE;
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return JNI_FALSE;
    if (!session->ctx || !session->model) return JNI_FALSE;

    const std::string grammarStr = jstringToString(env, grammar);
    if (grammarStr.empty()) {
        return JNI_FALSE;
    }

    std::vector<std::string> patterns;
    if (triggerPatterns != nullptr) {
        const jsize count = env->GetArrayLength(triggerPatterns);
        patterns.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto jPattern = reinterpret_cast<jstring>(env->GetObjectArrayElement(triggerPatterns, i));
            if (jPattern != nullptr) {
                const std::string pattern = jstringToString(env, jPattern);
                if (!pattern.empty()) {
                    patterns.push_back(pattern);
                }
                env->DeleteLocalRef(jPattern);
            }
        }
    }

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    session->toolCallGrammar.grammar = grammarStr;
    session->toolCallGrammar.lazy = !patterns.empty();
    session->toolCallGrammar.triggerPatterns = patterns;
    session->toolCallGrammar.triggerTokens.clear();
    session->toolCallGrammar.generationPrompt.clear();
    session->toolCallParserParams = common_chat_parser_params();
    session->hasToolCallParser = false;

    if (!rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to enable tool-call grammar");
        return JNI_FALSE;
    }

    LOGI("Tool-call grammar enabled. trigger_patterns=%zu", session->toolCallGrammar.triggerPatterns.size());
    return JNI_TRUE;
    }); // jniExceptionBarrier(nativeSetToolCallGrammar)
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeClearToolCallGrammar(JNIEnv * env, jclass clazz, jlong sessionPtr) {
    (void) clazz;

    // 崩溃防线：同 nativeSetToolCallGrammar，会重建 sampler chain。
    return jniExceptionBarrier(env, "nativeClearToolCallGrammar", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return JNI_FALSE;
    if (!session->ctx || !session->model) return JNI_FALSE;

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    resetToolCallState(session);

    if (!rebuildSamplerForSession(session)) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to clear tool-call grammar");
        return JNI_FALSE;
    }

    return JNI_TRUE;
    }); // jniExceptionBarrier(nativeClearToolCallGrammar)
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyChatTemplate(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
        jobjectArray roles,
        jobjectArray contents,
        jboolean enableThinking,
        jboolean addAssistant
) {
    (void) clazz;

    // 崩溃防线：chat template 走 minja/jinja 解释器，内部有大量可抛分配与反射路径。
    return jniExceptionBarrier(env, "nativeApplyChatTemplate", static_cast<jstring>(nullptr), [&]() -> jstring {
    if (roles == nullptr || contents == nullptr) return nullptr;
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return nullptr;
    if (!session->model || !session->chatTemplates) return nullptr;

    const jsize nRoles = env->GetArrayLength(roles);
    const jsize nContents = env->GetArrayLength(contents);
    if (nRoles <= 0 || nContents <= 0 || nRoles != nContents) return nullptr;

    std::vector<std::string> roleBuf;
    std::vector<std::string> contentBuf;
    roleBuf.reserve(static_cast<size_t>(nRoles));
    contentBuf.reserve(static_cast<size_t>(nRoles));

    for (jsize i = 0; i < nRoles; i++) {
        auto jrole = (jstring) env->GetObjectArrayElement(roles, i);
        auto jcontent = (jstring) env->GetObjectArrayElement(contents, i);
        roleBuf.push_back(jstringToString(env, jrole));
        contentBuf.push_back(jstringToString(env, jcontent));
        if (jrole) env->DeleteLocalRef(jrole);
        if (jcontent) env->DeleteLocalRef(jcontent);
    }

    std::vector<common_chat_msg> messages;
    if (!buildChatMessages(roleBuf, contentBuf, messages)) {
        return nullptr;
    }

    common_chat_templates_inputs inputs;
    inputs.messages = std::move(messages);
    inputs.add_generation_prompt = addAssistant == JNI_TRUE;
    inputs.enable_thinking = enableThinking == JNI_TRUE;
    inputs.use_jinja = true;

    try {
        const common_chat_params params = common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            return nullptr;
        }
        return bytesUtf8ToJstring(env, params.prompt);
    } catch (const std::exception & e) {
        LOGE("Failed to apply chat template: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Failed to apply chat template: unknown error");
        return nullptr;
    }
    }); // jniExceptionBarrier(nativeApplyChatTemplate)
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeApplyStructuredChatTemplate(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
        jstring messagesJson,
        jstring toolsJson,
        jboolean enableThinking,
        jboolean addAssistant
) {
    (void) clazz;

    // 崩溃防线：内部会 rebuildSamplerForSession（依赖 params.parser 构建 grammar sampler），
    // 而且函数体已有局部 try/catch 只覆盖 std::exception 的业务分支 —— 外层再包一层，
    // 保证任何路径的 C++ 异常都不会逃出 JNI 边界。
    return jniExceptionBarrier(env, "nativeApplyStructuredChatTemplate", static_cast<jstring>(nullptr), [&]() -> jstring {
    if (messagesJson == nullptr) return nullptr;
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return nullptr;
    if (!session->model || !session->chatTemplates || !session->ctx) return nullptr;

    const std::string messagesStr = jstringToString(env, messagesJson);
    const std::string toolsStr = jstringToString(env, toolsJson);

    const ToolCallGrammarConfigNative previousConfig = session->toolCallGrammar;
    const common_chat_parser_params previousParserParams = session->toolCallParserParams;
    const bool previousHasParser = session->hasToolCallParser;

    try {
        const auto messages = nlohmann::ordered_json::parse(messagesStr);
        const auto tools = toolsStr.empty()
            ? nlohmann::ordered_json()
            : nlohmann::ordered_json::parse(toolsStr);

        common_chat_templates_inputs inputs;
        inputs.messages = common_chat_msgs_parse_oaicompat(messages);
        inputs.tools = common_chat_tools_parse_oaicompat(tools);
        inputs.tool_choice = inputs.tools.empty()
            ? COMMON_CHAT_TOOL_CHOICE_NONE
            : COMMON_CHAT_TOOL_CHOICE_AUTO;
        inputs.add_generation_prompt = addAssistant == JNI_TRUE;
        inputs.enable_thinking = enableThinking == JNI_TRUE;
        inputs.use_jinja = true;

        const common_chat_params params = common_chat_templates_apply(session->chatTemplates.get(), inputs);
        if (params.prompt.empty()) {
            return nullptr;
        }

        session->toolCallGrammar = buildToolCallGrammarConfig(params);
        session->toolCallParserParams = common_chat_parser_params(params);
        session->toolCallParserParams.parse_tool_calls = true;
        session->hasToolCallParser = !params.parser.empty();
        if (session->hasToolCallParser) {
            session->toolCallParserParams.parser.load(params.parser);
        }

        if (!rebuildSamplerForSession(session)) {
            session->toolCallGrammar = previousConfig;
            session->toolCallParserParams = previousParserParams;
            session->hasToolCallParser = previousHasParser;
            (void) rebuildSamplerForSession(session);
            LOGE("Failed to apply structured chat template sampler state");
            return nullptr;
        }

        return bytesUtf8ToJstring(env, params.prompt);
    } catch (const std::exception & e) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to apply structured chat template: %s", e.what());
        return nullptr;
    } catch (...) {
        session->toolCallGrammar = previousConfig;
        session->toolCallParserParams = previousParserParams;
        session->hasToolCallParser = previousHasParser;
        (void) rebuildSamplerForSession(session);
        LOGE("Failed to apply structured chat template: unknown error");
        return nullptr;
    }
    }); // jniExceptionBarrier(nativeApplyStructuredChatTemplate)
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeParseToolCallResponse(
    JNIEnv * env,
    jclass clazz,
    jlong sessionPtr,
    jstring content
) {
    (void) clazz;

    // 崩溃防线：parser 走 minja 解析，异常同样不能逃出 JNI。
    return jniExceptionBarrier(env, "nativeParseToolCallResponse", static_cast<jstring>(nullptr), [&]() -> jstring {
    if (content == nullptr) return nullptr;
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) return nullptr;
    if (!session->hasToolCallParser) return nullptr;

    const std::string contentStr = jstringToString(env, content);
    if (contentStr.empty()) {
        return nullptr;
    }

    try {
        const common_chat_msg parsed = common_chat_parse(contentStr, false, session->toolCallParserParams);
        if (parsed.tool_calls.empty()) {
            return nullptr;
        }

        auto normalized = nlohmann::ordered_json::object();
        normalized["tool_calls"] = parsed.to_json_oaicompat()["tool_calls"];
        return bytesUtf8ToJstring(env, normalized.dump());
    } catch (const std::exception & e) {
        LOGE("Failed to parse tool-call response: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Failed to parse tool-call response: unknown error");
        return nullptr;
    }
    }); // jniExceptionBarrier(nativeParseToolCallResponse)
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_llama_LlamaNative_nativeGenerateStream(JNIEnv * env, jclass clazz, jlong sessionPtr, jstring prompt, jint maxTokens, jobject callback) {
    // 与 nativeCreateSession 同理：推理阶段 GPU 后端仍可能抛异常，
    // 不能让 C++ 异常逃出 JNI 边界导致进程 abort。
    return jniExceptionBarrier(env, "nativeGenerateStream", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {
    (void) clazz;

    if (callback == nullptr) return JNI_FALSE;
    // 用存活表取会话：release 与本函数并发时（用户点停止/切走页面），
    // 这里的 ptr 可能已是野指针，必须查到才继续。
    auto * session = acquireLiveSession(sessionPtr);
    if (session == nullptr) {
        LOGW("nativeGenerateStream: session not found (released?), aborting");
        return JNI_FALSE;
    }
    if (!session->model || !session->ctx || !session->sampler) return JNI_FALSE;

    session->cancel.store(false);

    // reset KV + sampler for a clean generation per request
    if (session->ctx) {
        llama_memory_t mem = llama_get_memory(session->ctx);
        if (mem) {
            llama_memory_clear(mem, true);
        }
    }
    if (session->sampler) {
        llama_sampler_reset(session->sampler);
    }

    const std::string promptStr = jstringToString(env, prompt);
    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    // 模型半损坏时 vocab 可能为 null，后续 llama_tokenize 会直接解引用。
    if (vocab == nullptr) {
        LOGE("nativeGenerateStream: model vocab is null");
        return JNI_FALSE;
    }

    // Resolve callback method
    jclass cbCls = env->GetObjectClass(callback);
    if (!cbCls) return JNI_FALSE;
    jmethodID midOnToken = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
    if (!midOnToken) return JNI_FALSE;

    // Tokenize prompt
    int32_t capacity = static_cast<int32_t>(promptStr.size()) + 8;
    std::vector<llama_token> promptTokens;
    promptTokens.resize(std::max(16, capacity));
    int32_t nPrompt = llama_tokenize(
        vocab,
        promptStr.c_str(),
        static_cast<int32_t>(promptStr.size()),
        promptTokens.data(),
        static_cast<int32_t>(promptTokens.size()),
        true,
        true
    );
    if (nPrompt < 0) {
        promptTokens.resize(static_cast<size_t>(-nPrompt));
        nPrompt = llama_tokenize(
            vocab,
            promptStr.c_str(),
            static_cast<int32_t>(promptStr.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            true,
            true
        );
    }
    if (nPrompt <= 0) {
        LOGE("Tokenize prompt failed");
        return JNI_FALSE;
    }
    promptTokens.resize(static_cast<size_t>(nPrompt));

    // Avoid prompts that end with EOG/EOS tokens (some vocabs add EOS automatically when add_special=true)
    while (!promptTokens.empty() && llama_vocab_is_eog(vocab, promptTokens.back())) {
        promptTokens.pop_back();
    }
    if (promptTokens.empty()) {
        LOGE("Prompt tokenization resulted in only EOG/EOS tokens");
        return JNI_FALSE;
    }

    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(session->ctx));
    // 上界钳制：maxTokens 来自 Java 侧，未做校验时可能传入 Int.MAX_VALUE。
    // 那会让下面的循环长跑到内存耗尽，且 `generatedTokens.size() * 8` 会整型溢出成负数，
    // 导致 detokBuf 分配过小 → llama_detokenize 越界写。
    // 32768 远超任何实际回复长度（MNN 侧同样是 8192 上限）。
    constexpr int kMaxNewTokensUpperBound = 32768;
    int maxNew = maxTokens <= 0 ? 256 : static_cast<int>(maxTokens);
    if (maxNew > kMaxNewTokensUpperBound) {
        LOGW("Requested maxTokens=%d exceeds upper bound; clamped to %d", maxNew, kMaxNewTokensUpperBound);
        maxNew = kMaxNewTokensUpperBound;
    }
    if (n_ctx > 0) {
        const int32_t reserveForGeneration = std::max<int32_t>(32, std::min<int32_t>(maxNew, n_ctx / 4));
        const int32_t maxPromptTokens = std::max<int32_t>(1, n_ctx - reserveForGeneration);
        if (static_cast<int32_t>(promptTokens.size()) > maxPromptTokens) {
            const size_t drop = promptTokens.size() - static_cast<size_t>(maxPromptTokens);
            const auto dropCount = static_cast<std::vector<llama_token>::difference_type>(drop);
            promptTokens.erase(promptTokens.begin(), promptTokens.begin() + dropCount);
            LOGI("Prompt truncated to fit context: kept=%d dropped=%zu n_ctx=%d", maxPromptTokens, drop, n_ctx);
        }
    }

    if (promptTokens.empty()) {
        LOGE("Prompt became empty after truncation");
        return JNI_FALSE;
    }

    LOGI(
        "Prefill decode start: prompt_tokens=%zu n_ctx=%d n_batch=%u max_new=%d",
        promptTokens.size(),
        n_ctx,
        llama_n_batch(session->ctx),
        maxNew
    );

    int32_t n_past = 0;

    // Evaluate prompt
    llama_batch batch = llama_batch_get_one(promptTokens.data(), static_cast<int32_t>(promptTokens.size()));
    // llama_batch_get_one() may leave batch.logits == nullptr (default behavior is: only last token outputs logits)
    // so never write to it unless it's allocated.
    if (batch.logits != nullptr && batch.n_tokens > 0) {
        batch.logits[batch.n_tokens - 1] = 1;
    }

    if (llama_model_has_encoder(session->model)) {
        if (llama_encode(session->ctx, batch) != 0) {
            LOGE("llama_encode failed");
            return JNI_FALSE;
        }

        llama_token decoder_start_token_id = llama_model_decoder_start_token(session->model);
        if (decoder_start_token_id == -1) {
            decoder_start_token_id = llama_vocab_bos(vocab);
        }

        batch = llama_batch_get_one(&decoder_start_token_id, 1);
        if (batch.logits != nullptr) {
            batch.logits[0] = 1;
        }
    }

    int32_t ret = llama_decode(session->ctx, batch);
    if (ret != 0 && ret != 1) {
        // 1 is a warning; 2 is aborted
        if (ret == 2) {
            LOGI("decode aborted (prompt)");
        } else {
            LOGE("llama_decode failed for prompt ret=%d", ret);
        }
        return JNI_FALSE;
    }

    // n_past for subsequent single-token decoding
    n_past = llama_model_has_encoder(session->model)
        ? 1
        : static_cast<int32_t>(promptTokens.size());

    prefillToolCallGenerationPrompt(session);

    // Generation loop
    std::vector<llama_token> generatedTokens;
    generatedTokens.reserve(static_cast<size_t>(maxNew));
    std::string prevDecoded;
    std::vector<char> detokBuf;

    for (int i = 0; i < maxNew; i++) {
        if (session->cancel.load()) {
            LOGI("generation cancelled");
            break;
        }

        const llama_token newToken = llama_sampler_sample(session->sampler, session->ctx, -1);
        llama_sampler_accept(session->sampler, newToken);

        if (i == 0) {
            LOGI("first sampled token=%d eog=%d", (int) newToken, (int) llama_vocab_is_eog(vocab, newToken));
        }

        if (llama_vocab_is_eog(vocab, newToken)) {
            break;
        }

        // Detokenize the generated token sequence to produce valid UTF-8 text.
        // Token pieces may split multi-byte sequences; emitting per-token pieces often results in mojibake.
        generatedTokens.push_back(newToken);

        // 注意用 size_t 运算再钳制到 int32_t：generatedTokens.size() * 8 在 size_t 下不会
        // 中途溢出，转 int32_t 前用 min 限幅，避免出现负的 detokCap 导致缓冲区过小。
        constexpr size_t kDetokCapUpper = 1u << 20; // 1M 字符，任何回复都远超此长度
        const size_t detokCapWide = std::min(kDetokCapUpper, generatedTokens.size() * 8 + 32);
        int32_t detokCap = std::max<int32_t>(64, static_cast<int32_t>(detokCapWide));
        detokBuf.resize(static_cast<size_t>(detokCap));

        int32_t nDetok = llama_detokenize(
            vocab,
            generatedTokens.data(),
            static_cast<int32_t>(generatedTokens.size()),
            detokBuf.data(),
            static_cast<int32_t>(detokBuf.size()),
            true,
            false
        );
        if (nDetok < 0) {
            detokBuf.resize(static_cast<size_t>(-nDetok));
            nDetok = llama_detokenize(
                vocab,
                generatedTokens.data(),
                static_cast<int32_t>(generatedTokens.size()),
                detokBuf.data(),
                static_cast<int32_t>(detokBuf.size()),
                true,
                false
            );
        }

        std::string decodedNow;
        if (nDetok > 0) {
            decodedNow.assign(detokBuf.data(), detokBuf.data() + nDetok);
        }

        std::string delta;
        if (!prevDecoded.empty() && decodedNow.rfind(prevDecoded, 0) == 0) {
            delta = decodedNow.substr(prevDecoded.size());
        } else {
            delta = decodedNow;
        }
        prevDecoded = decodedNow;

        if (!delta.empty()) {
            jstring jdelta = bytesUtf8ToJstring(env, delta);
            if (jdelta == nullptr || env->ExceptionCheck()) {
                env->ExceptionClear();
            } else {
                const jboolean keepGoing = env->CallBooleanMethod(callback, midOnToken, jdelta);
                env->DeleteLocalRef(jdelta);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    LOGE("Java callback threw exception; stopping generation");
                    break;
                }
                if (!keepGoing) {
                    break;
                }
            }
        }

        if (n_ctx > 0 && n_past >= n_ctx) {
            LOGI("context window reached: n_past=%d n_ctx=%d", n_past, n_ctx);
            break;
        }

        llama_token next = newToken;
        batch = llama_batch_get_one(&next, 1);
        if (batch.pos != nullptr) {
            batch.pos[0] = n_past;
        }
        if (batch.logits != nullptr) {
            batch.logits[0] = 1;
        }
        ret = llama_decode(session->ctx, batch);
        if (ret != 0 && ret != 1) {
            if (ret == 2) {
                LOGI("decode aborted");
                break;
            }
            LOGE("llama_decode failed ret=%d", ret);
            return JNI_FALSE;
        }

        n_past += 1;
    }

    return JNI_TRUE;
    }); // jniExceptionBarrier(nativeGenerateStream)
}

#endif
