//
//  mnnllmnative.cpp
//  MNN LLM Engine JNI wrapper
//
//  Based on MnnLlmChat official implementation
//

#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <android/log.h>
#include <fstream>
#include <sstream>
#include <map>
#include <mutex>
#include <atomic>
#include <set>
#include <rapidjson/document.h>

// MNN LLM headers
#include <MNN/expr/Expr.hpp>
#include <MNN/expr/Module.hpp>
#include <llm/llm.hpp>
#if defined(LLM_USE_MINJA) && __has_include("minja/chat_template.hpp")
#include "minja/chat_template.hpp"
#define MNN_HAS_MINJA 1
#else
#define MNN_HAS_MINJA 0
#endif

#define TAG "MNNLlmNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace MNN;
using namespace MNN::Transformer;

// =======================
// Cancellation Support
// =======================

// 全局取消标志映射 (llmPtr -> shouldCancel)
static std::mutex gCancelMutex;
static std::map<jlong, bool> gCancelFlags;

// =======================
// Llm Handle Liveness
// =======================
//
// nativeReleaseLlm / nativeReleaseSession 与推理调用之间没有锁保护：
// Kotlin 侧在锁外持有 ptr 调用 native 长任务（生成 / tokenize / 波形），
// 若此时另一线程调用 release，指针立刻变悬垂 → 下一次解引用即 UAF。
//
// 这里维护「存活 Llm 集合」：所有入口先用 acquireLiveLlm 校验，
// release 先摘表再 destroy。摘表之后的新调用会安全失败（返回空/False），
// 无法做到「等待在途调用结束」——那需要引入 per-handle 引用计数与条件变量，
// 改动面太大；但可以保证不再有**新增**的 use-after-free。
static std::mutex gLiveLlmMutex;
static std::set<jlong> gLiveLlms;

void publishLiveLlm(jlong llmPtr) {
    if (llmPtr == 0) return;
    std::lock_guard<std::mutex> lock(gLiveLlmMutex);
    gLiveLlms.insert(llmPtr);
}

bool acquireLiveLlm(jlong llmPtr) {
    if (llmPtr == 0) return false;
    std::lock_guard<std::mutex> lock(gLiveLlmMutex);
    return gLiveLlms.count(llmPtr) > 0;
}

bool retireLiveLlm(jlong llmPtr) {
    if (llmPtr == 0) return false;
    std::lock_guard<std::mutex> lock(gLiveLlmMutex);
    return gLiveLlms.erase(llmPtr) > 0;
}

// 设置取消标志
void setCancelFlag(jlong llmPtr, bool value) {
    std::lock_guard<std::mutex> lock(gCancelMutex);
    gCancelFlags[llmPtr] = value;
}

// 检查取消标志
bool checkCancelFlag(jlong llmPtr) {
    std::lock_guard<std::mutex> lock(gCancelMutex);
    auto it = gCancelFlags.find(llmPtr);
    return (it != gCancelFlags.end()) && it->second;
}

// 清除取消标志
void clearCancelFlag(jlong llmPtr) {
    std::lock_guard<std::mutex> lock(gCancelMutex);
    gCancelFlags.erase(llmPtr);
}

// =======================
// Audio Callback Support
// =======================

struct AudioCallbackHolder {
    JavaVM* jvm = nullptr;
    jobject callbackGlobalRef = nullptr;
    jmethodID onAudioDataMethod = nullptr;
    // 回调「代次」。每次 clearAudioCallback 递增，在途的 invokeAudioCallback
    // 在真正调用前比对代次，不一致就放弃 —— 避免使用已被 DeleteGlobalRef 的引用。
    uint64_t epoch = 0;
};

static std::mutex gAudioCallbackMutex;
static std::map<jlong, AudioCallbackHolder> gAudioCallbacks;
// 全局代次：只需要单调递增，不需要 per-ptr（旧代次的调用一律放弃）。
static std::atomic<uint64_t> gAudioCallbackEpoch{0};

void clearAudioCallback(JNIEnv* env, jlong llmPtr) {
    AudioCallbackHolder holder;
    bool hasHolder = false;
    uint64_t nextEpoch = 0;
    {
        std::lock_guard<std::mutex> lock(gAudioCallbackMutex);
        // 无论表里有没有，都推进代次：让所有在途回调立即作废。
        nextEpoch = ++gAudioCallbackEpoch;
        auto it = gAudioCallbacks.find(llmPtr);
        if (it != gAudioCallbacks.end()) {
            holder = it->second;
            holder.epoch = nextEpoch;
            gAudioCallbacks.erase(it);
            hasHolder = true;
        }
    }
    (void)nextEpoch;

    if (hasHolder && env != nullptr && holder.callbackGlobalRef != nullptr) {
        // 摘表 + 递增代次已完成，此刻在途回调（持有旧 epoch 的副本）会自行放弃，
        // 可以安全地回收全局引用。
        env->DeleteGlobalRef(holder.callbackGlobalRef);
    }
}

bool invokeAudioCallback(jlong llmPtr, const float* data, size_t size, bool isLastChunk) {
    AudioCallbackHolder holder;
    {
        std::lock_guard<std::mutex> lock(gAudioCallbackMutex);
        auto it = gAudioCallbacks.find(llmPtr);
        if (it == gAudioCallbacks.end()) {
            return false;
        }
        holder = it->second;
        holder.epoch = gAudioCallbackEpoch.load();
    }

    if (holder.jvm == nullptr || holder.callbackGlobalRef == nullptr || holder.onAudioDataMethod == nullptr) {
        return false;
    }

    JNIEnv* env = nullptr;
    bool needDetach = false;
    int getEnvResult = holder.jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_EDETACHED) {
        if (holder.jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread for audio callback");
            return false;
        }
        needDetach = true;
    } else if (getEnvResult != JNI_OK || env == nullptr) {
        LOGE("Failed to get JNIEnv for audio callback: %d", getEnvResult);
        return false;
    }

    // attach 这一小段时间里回调可能已被注销（全局引用随之被删），
    // 调用前复查代次，避免使用悬垂的 jobject。
    {
        std::lock_guard<std::mutex> lock(gAudioCallbackMutex);
        auto it = gAudioCallbacks.find(llmPtr);
        if (it == gAudioCallbacks.end() || gAudioCallbackEpoch.load() != holder.epoch) {
            if (needDetach) {
                holder.jvm->DetachCurrentThread();
            }
            return false;
        }
    }

    jfloatArray audioDataArray = env->NewFloatArray(static_cast<jsize>(size));
    if (audioDataArray == nullptr) {
        if (needDetach) {
            holder.jvm->DetachCurrentThread();
        }
        return false;
    }
    env->SetFloatArrayRegion(audioDataArray, 0, static_cast<jsize>(size), data);

    jboolean shouldContinue = env->CallBooleanMethod(
        holder.callbackGlobalRef,
        holder.onAudioDataMethod,
        audioDataArray,
        isLastChunk ? JNI_TRUE : JNI_FALSE
    );

    env->DeleteLocalRef(audioDataArray);

    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        shouldContinue = JNI_FALSE;
    }

    if (needDetach) {
        holder.jvm->DetachCurrentThread();
    }

    return shouldContinue == JNI_TRUE;
}

// =======================
// Helper Functions
// =======================

std::string jstringToString(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* cstr = env->GetStringUTFChars(jstr, nullptr);
    // GetStringUTFChars 在 OOM 时返回 nullptr 并挂起 OutOfMemoryError。
    // 不判空直接构造 std::string，libc++ 会在 strlen(nullptr) 上 SIGSEGV —— 这是 native 崩溃。
    if (cstr == nullptr) {
        MNN_ERROR("jstringToString: GetStringUTFChars returned null (OOM); degrading to empty string\n");
        return "";
    }
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

jstring stringToJstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

ChatMessages parseChatHistory(JNIEnv* env, jobject jhistory) {
    ChatMessages history;
    if (jhistory == nullptr) {
        return history;
    }

    // FindClass / GetMethodID 失败时返回 null 且挂起 pending 异常。
    // 后续 CallIntMethod(null methodID) 会直接把 pending 异常升级为 hard abort，
    // 而这里只是「拿不到历史」——降级成空历史远好过整机崩溃。
    jclass listClass = env->FindClass("java/util/List");
    if (listClass == nullptr) {
        MNN_ERROR("parseChatHistory: List class not found\n");
        env->ExceptionClear();
        return history;
    }
    jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
    jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
    if (sizeMethod == nullptr || getMethod == nullptr) {
        MNN_ERROR("parseChatHistory: List methods not found\n");
        env->ExceptionClear();
        env->DeleteLocalRef(listClass);
        return history;
    }
    jint listSize = env->CallIntMethod(jhistory, sizeMethod);
    // listSize 由外部 List 决定；负数或异常（CallIntMethod 抛错时返回 0）都直接退出。
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(listClass);
        return history;
    }
    if (listSize <= 0) {
        env->DeleteLocalRef(listClass);
        return history;
    }

    jclass pairClass = env->FindClass("kotlin/Pair");
    if (pairClass == nullptr) {
        MNN_ERROR("parseChatHistory: Pair class not found\n");
        env->ExceptionClear();
        env->DeleteLocalRef(listClass);
        return history;
    }
    jmethodID getFirstMethod = env->GetMethodID(pairClass, "getFirst", "()Ljava/lang/Object;");
    jmethodID getSecondMethod = env->GetMethodID(pairClass, "getSecond", "()Ljava/lang/Object;");
    if (getFirstMethod == nullptr || getSecondMethod == nullptr) {
        MNN_ERROR("parseChatHistory: Pair methods not found\n");
        env->ExceptionClear();
        env->DeleteLocalRef(pairClass);
        env->DeleteLocalRef(listClass);
        return history;
    }

    // 限制最大轮数，避免异常输入（例如超大 List）把 native 内存撑爆。
    constexpr jint kMaxHistoryItems = 4096;
    if (listSize > kMaxHistoryItems) {
        MNN_ERROR("parseChatHistory: history too long (%d), truncated to %d\n", listSize, kMaxHistoryItems);
        listSize = kMaxHistoryItems;
    }

    for (jint i = 0; i < listSize; i++) {
        jobject pairObj = env->CallObjectMethod(jhistory, getMethod, i);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }
        if (pairObj == nullptr) {
            continue;
        }

        jobject roleObj = env->CallObjectMethod(pairObj, getFirstMethod);
        jobject contentObj = env->CallObjectMethod(pairObj, getSecondMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            if (roleObj) env->DeleteLocalRef(roleObj);
            if (contentObj) env->DeleteLocalRef(contentObj);
            env->DeleteLocalRef(pairObj);
            break;
        }

        if (roleObj != nullptr && contentObj != nullptr) {
            std::string role = jstringToString(env, (jstring)roleObj);
            std::string content = jstringToString(env, (jstring)contentObj);
            history.emplace_back(role, content);
        }

        if (roleObj) env->DeleteLocalRef(roleObj);
        if (contentObj) env->DeleteLocalRef(contentObj);
        env->DeleteLocalRef(pairObj);
    }

    env->DeleteLocalRef(listClass);
    env->DeleteLocalRef(pairClass);
    return history;
}

#if MNN_HAS_MINJA
std::string readTemplateToken(const rapidjson::Value& value) {
    if (value.IsString()) {
        return value.GetString();
    }
    if (value.IsObject() && value.HasMember("content") && value["content"].IsString()) {
        return value["content"].GetString();
    }
    return "";
}

bool parseJsonArrayDocument(const std::string& json, rapidjson::Document& document) {
    document.Parse(json.c_str());
    return !document.HasParseError() && document.IsArray();
}

std::string applyStructuredChatTemplate(Llm* llm, const std::string& messagesJson, const std::string& toolsJson) {
    if (llm == nullptr || messagesJson.empty()) {
        return "";
    }

    rapidjson::Document messagesDoc;
    if (!parseJsonArrayDocument(messagesJson, messagesDoc)) {
        LOGE("Invalid structured messages json");
        return "";
    }

    rapidjson::Document configDoc;
    std::string configJson = llm->dump_config();
    configDoc.Parse(configJson.c_str());
    if (configDoc.HasParseError() || !configDoc.IsObject()) {
        LOGE("Invalid llm config json");
        return "";
    }

    if (!configDoc.HasMember("jinja") || !configDoc["jinja"].IsObject()) {
        LOGE("LLM config has no jinja section");
        return "";
    }

    const auto& jinja = configDoc["jinja"];
    if (!jinja.HasMember("chat_template")) {
        LOGE("LLM config has no jinja.chat_template");
        return "";
    }

    std::string chatTemplate = readTemplateToken(jinja["chat_template"]);
    if (chatTemplate.empty()) {
        LOGE("LLM config jinja.chat_template is empty");
        return "";
    }

    std::string bosToken;
    std::string eosToken;
    if (jinja.HasMember("bos")) {
        bosToken = readTemplateToken(jinja["bos"]);
    }
    if (jinja.HasMember("eos")) {
        eosToken = readTemplateToken(jinja["eos"]);
    }

    minja::chat_template tmpl(chatTemplate, bosToken, eosToken);
    minja::chat_template_inputs inputs;
    inputs.messages.CopyFrom(messagesDoc, inputs.messages.GetAllocator());
    inputs.add_generation_prompt = true;

    if (!toolsJson.empty()) {
        rapidjson::Document toolsDoc;
        if (!parseJsonArrayDocument(toolsJson, toolsDoc)) {
            LOGE("Invalid structured tools json");
            return "";
        }
        inputs.tools.CopyFrom(toolsDoc, inputs.tools.GetAllocator());
    } else {
        inputs.tools.SetNull();
    }

    if (jinja.HasMember("context") && jinja["context"].IsObject()) {
        inputs.extra_context.CopyFrom(jinja["context"], inputs.extra_context.GetAllocator());
    } else {
        inputs.extra_context.SetNull();
    }

    return tmpl.apply(inputs);
}
#else
std::string applyStructuredChatTemplate(Llm* llm, const std::string& messagesJson, const std::string& toolsJson) {
    LOGE("Structured chat template requires minja/chat_template.hpp");
    return "";
}
#endif

std::string jsonEscape(const std::string& input) {
    std::string output;
    output.reserve(input.size() + 16);
    for (char ch : input) {
        switch (ch) {
            case '\\':
                output += "\\\\";
                break;
            case '"':
                output += "\\\"";
                break;
            case '\b':
                output += "\\b";
                break;
            case '\f':
                output += "\\f";
                break;
            case '\n':
                output += "\\n";
                break;
            case '\r':
                output += "\\r";
                break;
            case '\t':
                output += "\\t";
                break;
            default:
                output += ch;
                break;
        }
    }
    return output;
}

void appendIntVectorJson(std::ostringstream& oss, const std::vector<int>& values) {
    oss << "[";
    for (size_t i = 0; i < values.size(); ++i) {
        if (i > 0) {
            oss << ",";
        }
        oss << values[i];
    }
    oss << "]";
}

const char* llmStatusToString(LlmStatus status) {
    switch (status) {
        case LlmStatus::RUNNING:
            return "RUNNING";
        case LlmStatus::NORMAL_FINISHED:
            return "NORMAL_FINISHED";
        case LlmStatus::MAX_TOKENS_FINISHED:
            return "MAX_TOKENS_FINISHED";
        case LlmStatus::USER_CANCEL:
            return "USER_CANCEL";
        case LlmStatus::INTERNAL_ERROR:
            return "INTERNAL_ERROR";
        default:
            return "UNKNOWN";
    }
}

std::string contextToJson(const LlmContext* context) {
    if (context == nullptr) {
        return "";
    }

    std::ostringstream oss;
    oss << "{";
    oss << "\"prompt_len\":" << context->prompt_len << ",";
    oss << "\"gen_seq_len\":" << context->gen_seq_len << ",";
    oss << "\"all_seq_len\":" << context->all_seq_len << ",";
    oss << "\"load_us\":" << context->load_us << ",";
    oss << "\"vision_us\":" << context->vision_us << ",";
    oss << "\"audio_us\":" << context->audio_us << ",";
    oss << "\"prefill_us\":" << context->prefill_us << ",";
    oss << "\"decode_us\":" << context->decode_us << ",";
    oss << "\"sample_us\":" << context->sample_us << ",";
    oss << "\"pixels_mp\":" << context->pixels_mp << ",";
    oss << "\"audio_input_s\":" << context->audio_input_s << ",";
    oss << "\"current_token\":" << context->current_token << ",";
    oss << "\"status_code\":" << static_cast<int>(context->status) << ",";
    oss << "\"status\":\"" << llmStatusToString(context->status) << "\",";
    oss << "\"generate_str\":\"" << jsonEscape(context->generate_str) << "\",";
    oss << "\"history_tokens\":";
    appendIntVectorJson(oss, context->history_tokens);
    oss << ",";
    oss << "\"output_tokens\":";
    appendIntVectorJson(oss, context->output_tokens);
    oss << "}";
    return oss.str();
}

// =======================
// LLM Instance Management
// =======================

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCreateLlm(
    JNIEnv* env, jclass clazz, jstring jconfigPath) {
    
    std::string configPath = jstringToString(env, jconfigPath);
    LOGD("Creating LLM from config: %s", configPath.c_str());
    
    try {
        // 使用 MNN LLM 引擎创建实例（但不加载）
        // 按照官方 llm_session.cpp 的做法，先创建实例
        Llm* llm = Llm::createLLM(configPath);
        if (llm == nullptr) {
            LOGE("Failed to create LLM instance");
            return 0;
        }
        
        // 注意：这里不调用 load()，让上层在设置完配置后再调用 load()
        // 这是关键：配置必须在 load() 之前设置！
        publishLiveLlm(reinterpret_cast<jlong>(llm));
        LOGI("LLM instance created at %p (not loaded yet)", llm);
        return reinterpret_cast<jlong>(llm);
        
    } catch (const std::exception& e) {
        LOGE("Exception creating LLM: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("Unknown exception creating LLM");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokens(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jtext) {

    if (llmPtr == 0) return 0;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return 0;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    std::string text = jstringToString(env, jtext);

    try {
        std::vector<int> tokens = llm->tokenizer_encode(text);
        return static_cast<jint>(tokens.size());
    } catch (const std::exception& e) {
        LOGE("Exception in countTokens: %s", e.what());
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeLoadLlm(
    JNIEnv* env, jclass clazz, jlong llmPtr) {
    
    if (llmPtr == 0) return JNI_FALSE;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    LOGD("Loading LLM model at %p", llm);
    
    try {
        // 加载模型（必须在配置设置之后调用）
        if (!llm->load()) {
            LOGE("Failed to load LLM model");
            return JNI_FALSE;
        }
        
        LOGI("LLM model loaded successfully");
        return JNI_TRUE;
        
    } catch (const std::exception& e) {
        LOGE("Exception loading LLM: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeReleaseLlm(
    JNIEnv* env, jclass clazz, jlong llmPtr) {
    
    if (llmPtr == 0) return;

    // 先摘存活表：此后任何新的 native 调用都会在 acquireLiveLlm 处安全失败，
    // 不会再拿到这个即将被 destroy 的指针。
    if (!retireLiveLlm(llmPtr)) {
        LOGW("nativeReleaseLlm: llm 0x%llx already released, ignoring (double-release)",
             static_cast<unsigned long long>(llmPtr));
        return;
    }
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    LOGD("Releasing LLM at %p", llm);
    
    try {
        clearAudioCallback(env, llmPtr);
        clearCancelFlag(llmPtr);
        Llm::destroy(llm);
        LOGI("LLM released successfully");
    } catch (const std::exception& e) {
        LOGE("Exception releasing LLM: %s", e.what());
    }
}

// =======================
// Tokenization
// =======================

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeTokenize(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jtext) {
    
    if (llmPtr == 0) return nullptr;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    std::string text = jstringToString(env, jtext);
    
    try {
        // 使用 LLM 的 tokenizer 编码
        std::vector<int> tokens = llm->tokenizer_encode(text);
        
        // 转换为 Java int array
        jintArray result = env->NewIntArray(tokens.size());
        if (result != nullptr) {
            env->SetIntArrayRegion(result, 0, tokens.size(), tokens.data());
        }
        
        return result;
        
    } catch (const std::exception& e) {
        LOGE("Exception in tokenize: %s", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeDetokenize(
    JNIEnv* env, jclass clazz, jlong llmPtr, jint token) {
    
    if (llmPtr == 0) return nullptr;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    
    try {
        // 使用 LLM 的 tokenizer 解码
        std::string text = llm->tokenizer_decode(token);
        return stringToJstring(env, text);
        
    } catch (const std::exception& e) {
        LOGE("Exception in detokenize: %s", e.what());
        return nullptr;
    }
}

// =======================
// Text Generation (Streaming)
// =======================

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerate(
    JNIEnv* env, jclass clazz, 
    jlong llmPtr, 
    jstring jprompt,
    jint maxTokens,
    jobject callback) {
    
    if (llmPtr == 0) return nullptr;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    std::string prompt = jstringToString(env, jprompt);
    
    try {
        // 编码输入
        std::vector<int> inputTokens = llm->tokenizer_encode(prompt);
        LOGD("Input tokens: %zu", inputTokens.size());
        
        // 生成输出（使用流式输出）
        std::stringstream outputStream;
        llm->response(inputTokens, &outputStream, nullptr, maxTokens);
        
        // 返回完整响应
        std::string response = outputStream.str();
        LOGD("Generated response: %zu chars", response.size());
        
        return stringToJstring(env, response);
        
    } catch (const std::exception& e) {
        LOGE("Exception in generate: %s", e.what());
        return nullptr;
    }
}

// =======================
// Streaming Generation with Callback
// =======================

struct StreamContext {
    JavaVM* jvm;
    jobject callbackGlobalRef;
    jmethodID onTokenMethod;
    std::string buffer;
    bool shouldStop = false;
    jlong llmPtr = 0;  // 添加 llm 指针用于检查取消标志
};

static jboolean runStreamGenerationWithInputIds(
    JNIEnv* env,
    jlong llmPtr,
    const std::vector<int>& inputTokens,
    jint maxTokens,
    jobject callback) {

    if (llmPtr == 0) return JNI_FALSE;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    jobject callbackGlobalRef = nullptr;

    try {
        JavaVM* jvm = nullptr;
        if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
            LOGE("Failed to get JavaVM");
            return JNI_FALSE;
        }

        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(callbackClass);

        if (onTokenMethod == nullptr) {
            LOGE("Failed to find onToken method in callback");
            return JNI_FALSE;
        }

        callbackGlobalRef = env->NewGlobalRef(callback);
        if (callbackGlobalRef == nullptr) {
            LOGE("Failed to create global reference for callback");
            return JNI_FALSE;
        }

        StreamContext context;
        context.jvm = jvm;
        context.callbackGlobalRef = callbackGlobalRef;
        context.onTokenMethod = onTokenMethod;
        context.llmPtr = llmPtr;

        setCancelFlag(llmPtr, false);

        class CallbackStream : public std::streambuf {
        public:
            CallbackStream(StreamContext* ctx) : mContext(ctx) {}

            void flushToCallback() {
                if (mContext->buffer.empty() || mContext->shouldStop) {
                    return;
                }

                const std::string endMarker = "<eop>";
                auto pos = mContext->buffer.find(endMarker);
                std::string payload = mContext->buffer;
                if (pos != std::string::npos) {
                    payload = mContext->buffer.substr(0, pos);
                    mContext->shouldStop = true;
                }

                if (payload.empty()) {
                    mContext->buffer.clear();
                    return;
                }

                bool needDetach = false;
                JNIEnv* env = nullptr;

                int getEnvResult = mContext->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
                if (getEnvResult == JNI_EDETACHED) {
                    if (mContext->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to attach thread");
                        return;
                    }
                    needDetach = true;
                } else if (getEnvResult != JNI_OK) {
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get JNIEnv: %d", getEnvResult);
                    return;
                }

                try {
                    jstring jtoken = env->NewStringUTF(payload.c_str());
                    if (jtoken != nullptr) {
                        jboolean shouldContinue = env->CallBooleanMethod(
                            mContext->callbackGlobalRef,
                            mContext->onTokenMethod,
                            jtoken
                        );
                        env->DeleteLocalRef(jtoken);

                        if (env->ExceptionCheck()) {
                            env->ExceptionDescribe();
                            env->ExceptionClear();
                            mContext->shouldStop = true;
                        } else if (!shouldContinue) {
                            mContext->shouldStop = true;
                            __android_log_print(ANDROID_LOG_DEBUG, TAG, "Stream stopped by callback");
                        }
                    } else {
                        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create jstring");
                    }
                } catch (...) {
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in callback");
                    mContext->shouldStop = true;
                }

                if (needDetach) {
                    mContext->jvm->DetachCurrentThread();
                }

                mContext->buffer.clear();
            }

        protected:
            virtual std::streamsize xsputn(const char* s, std::streamsize n) override {
                if (mContext->shouldStop || checkCancelFlag(mContext->llmPtr) || n <= 0) {
                    if (checkCancelFlag(mContext->llmPtr)) {
                        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Generation cancelled by user");
                        mContext->shouldStop = true;
                    }
                    return 0;
                }

                std::string completeChars = extractCompleteUtf8(s, static_cast<size_t>(n));
                if (completeChars.empty()) {
                    return n;
                }

                mContext->buffer.append(completeChars);
                
                if (shouldFlush(completeChars)) {
                    flushToCallback();
                }

                return n;
            }

        private:
            static int utf8CharLength(unsigned char byte) {
                if ((byte & 0x80) == 0) return 1;
                if ((byte & 0xE0) == 0xC0) return 2;
                if ((byte & 0xF0) == 0xE0) return 3;
                if ((byte & 0xF8) == 0xF0) return 4;
                return 0;
            }

            static bool containsFlushDelimiter(const std::string& text) {
                return text.find('\n') != std::string::npos ||
                       text.find('.') != std::string::npos ||
                       text.find('!') != std::string::npos ||
                       text.find('?') != std::string::npos ||
                       text.find("\xE3\x80\x82") != std::string::npos ||
                       text.find("\xEF\xBC\x81") != std::string::npos ||
                       text.find("\xEF\xBC\x9F") != std::string::npos;
            }

            std::string extractCompleteUtf8(const char* s, size_t n) {
                mPendingUtf8Bytes.append(s, n);

                size_t i = 0;
                std::string completeChars;
                while (i < mPendingUtf8Bytes.size()) {
                    int length = utf8CharLength(static_cast<unsigned char>(mPendingUtf8Bytes[i]));
                    if (length == 0 || i + static_cast<size_t>(length) > mPendingUtf8Bytes.size()) {
                        break;
                    }
                    completeChars.append(mPendingUtf8Bytes, i, static_cast<size_t>(length));
                    i += static_cast<size_t>(length);
                }

                if (i > 0) {
                    mPendingUtf8Bytes.erase(0, i);
                }

                return completeChars;
            }

            bool shouldFlush(const std::string& completeChars) const {
                return mContext->buffer.find("<eop>") != std::string::npos ||
                       mContext->buffer.size() >= 16 ||
                       containsFlushDelimiter(completeChars);
            }

            StreamContext* mContext;
            std::string mPendingUtf8Bytes;
        };
        
        CallbackStream callbackBuf(&context);
        std::ostream outputStream(&callbackBuf);

        llm->reset();

        int maxNewTokens = maxTokens > 0 ? static_cast<int>(maxTokens) : 512;
        if (maxNewTokens > 8192) {
            maxNewTokens = 8192;
        }

        int currentSize = 0;

        llm->response(inputTokens, &outputStream, "<eop>", 1);
        currentSize++;

        while (!context.shouldStop && currentSize < maxNewTokens && !checkCancelFlag(llmPtr)) {
            llm->generate(1);
            currentSize++;
        }

        if (!context.buffer.empty() && !context.shouldStop) {
            callbackBuf.flushToCallback();
        }

        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);
        
        LOGI("Stream generation completed");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("Exception in generateStream: %s", e.what());
        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in generateStream");
        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);
        return JNI_FALSE;
    }
}

static jboolean runStreamGenerationWithHistory(
    JNIEnv* env,
    jlong llmPtr,
    const ChatMessages& history,
    jint maxTokens,
    jobject callback) {

    if (llmPtr == 0) return JNI_FALSE;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    jobject callbackGlobalRef = nullptr;

    try {
        JavaVM* jvm = nullptr;
        if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
            LOGE("Failed to get JavaVM");
            return JNI_FALSE;
        }

        jclass callbackClass = env->GetObjectClass(callback);
        jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(callbackClass);

        if (onTokenMethod == nullptr) {
            LOGE("Failed to find onToken method in callback");
            return JNI_FALSE;
        }

        callbackGlobalRef = env->NewGlobalRef(callback);
        if (callbackGlobalRef == nullptr) {
            LOGE("Failed to create global reference for callback");
            return JNI_FALSE;
        }

        StreamContext context;
        context.jvm = jvm;
        context.callbackGlobalRef = callbackGlobalRef;
        context.onTokenMethod = onTokenMethod;
        context.llmPtr = llmPtr;

        setCancelFlag(llmPtr, false);

        class CallbackStream : public std::streambuf {
        public:
            CallbackStream(StreamContext* ctx) : mContext(ctx) {}

            void flushToCallback() {
                if (mContext->buffer.empty() || mContext->shouldStop) {
                    return;
                }

                const std::string endMarker = "<eop>";
                auto pos = mContext->buffer.find(endMarker);
                std::string payload = mContext->buffer;
                if (pos != std::string::npos) {
                    payload = mContext->buffer.substr(0, pos);
                    mContext->shouldStop = true;
                }

                if (payload.empty()) {
                    mContext->buffer.clear();
                    return;
                }

                bool needDetach = false;
                JNIEnv* env = nullptr;

                int getEnvResult = mContext->jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
                if (getEnvResult == JNI_EDETACHED) {
                    if (mContext->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to attach thread");
                        return;
                    }
                    needDetach = true;
                } else if (getEnvResult != JNI_OK) {
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get JNIEnv: %d", getEnvResult);
                    return;
                }

                try {
                    jstring jtoken = env->NewStringUTF(payload.c_str());
                    if (jtoken != nullptr) {
                        jboolean shouldContinue = env->CallBooleanMethod(
                            mContext->callbackGlobalRef,
                            mContext->onTokenMethod,
                            jtoken
                        );
                        env->DeleteLocalRef(jtoken);

                        if (env->ExceptionCheck()) {
                            env->ExceptionDescribe();
                            env->ExceptionClear();
                            mContext->shouldStop = true;
                        } else if (!shouldContinue) {
                            mContext->shouldStop = true;
                            __android_log_print(ANDROID_LOG_DEBUG, TAG, "Stream stopped by callback");
                        }
                    } else {
                        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create jstring");
                    }
                } catch (...) {
                    __android_log_print(ANDROID_LOG_ERROR, TAG, "Exception in callback");
                    mContext->shouldStop = true;
                }

                if (needDetach) {
                    mContext->jvm->DetachCurrentThread();
                }

                mContext->buffer.clear();
            }

        protected:
            virtual std::streamsize xsputn(const char* s, std::streamsize n) override {
                if (mContext->shouldStop || checkCancelFlag(mContext->llmPtr) || n <= 0) {
                    if (checkCancelFlag(mContext->llmPtr)) {
                        __android_log_print(ANDROID_LOG_DEBUG, TAG, "Generation cancelled by user");
                        mContext->shouldStop = true;
                    }
                    return 0;
                }

                std::string completeChars = extractCompleteUtf8(s, static_cast<size_t>(n));
                if (completeChars.empty()) {
                    return n;
                }

                mContext->buffer.append(completeChars);

                if (shouldFlush(completeChars)) {
                    flushToCallback();
                }

                return n;
            }

        private:
            static int utf8CharLength(unsigned char byte) {
                if ((byte & 0x80) == 0) return 1;
                if ((byte & 0xE0) == 0xC0) return 2;
                if ((byte & 0xF0) == 0xE0) return 3;
                if ((byte & 0xF8) == 0xF0) return 4;
                return 0;
            }

            static bool containsFlushDelimiter(const std::string& text) {
                return text.find('\n') != std::string::npos ||
                       text.find('.') != std::string::npos ||
                       text.find('!') != std::string::npos ||
                       text.find('?') != std::string::npos ||
                       text.find("\xE3\x80\x82") != std::string::npos ||
                       text.find("\xEF\xBC\x81") != std::string::npos ||
                       text.find("\xEF\xBC\x9F") != std::string::npos;
            }

            std::string extractCompleteUtf8(const char* s, size_t n) {
                mPendingUtf8Bytes.append(s, n);

                size_t i = 0;
                std::string completeChars;
                while (i < mPendingUtf8Bytes.size()) {
                    int length = utf8CharLength(static_cast<unsigned char>(mPendingUtf8Bytes[i]));
                    if (length == 0 || i + static_cast<size_t>(length) > mPendingUtf8Bytes.size()) {
                        break;
                    }
                    completeChars.append(mPendingUtf8Bytes, i, static_cast<size_t>(length));
                    i += static_cast<size_t>(length);
                }

                if (i > 0) {
                    mPendingUtf8Bytes.erase(0, i);
                }

                return completeChars;
            }

            bool shouldFlush(const std::string& completeChars) const {
                return mContext->buffer.find("<eop>") != std::string::npos ||
                       mContext->buffer.size() >= 16 ||
                       containsFlushDelimiter(completeChars);
            }

            StreamContext* mContext;
            std::string mPendingUtf8Bytes;
        };

        CallbackStream callbackBuf(&context);
        std::ostream outputStream(&callbackBuf);

        llm->reset();

        int maxNewTokens = maxTokens > 0 ? static_cast<int>(maxTokens) : 512;
        if (maxNewTokens > 8192) {
            maxNewTokens = 8192;
        }

        int currentSize = 0;

        llm->response(history, &outputStream, "<eop>", 1);
        currentSize++;

        while (!context.shouldStop && currentSize < maxNewTokens && !checkCancelFlag(llmPtr)) {
            llm->generate(1);
            currentSize++;
        }

        if (!context.buffer.empty() && !context.shouldStop) {
            callbackBuf.flushToCallback();
        }

        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);

        LOGI("Direct history stream generation completed");
        return JNI_TRUE;

    } catch (const std::exception& e) {
        LOGE("Exception in direct history generateStream: %s", e.what());
        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);
        return JNI_FALSE;
    } catch (...) {
        LOGE("Unknown exception in direct history generateStream");
        if (callbackGlobalRef != nullptr) {
            env->DeleteGlobalRef(callbackGlobalRef);
        }
        clearCancelFlag(llmPtr);
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStream(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jobject jhistory,
    jint maxTokens,
    jobject callback) {

    if (llmPtr == 0) return JNI_FALSE;

    try {
        // parseChatHistory 内部有 std::string / vector 扩容等可抛分配操作，
        // 必须在 try 之内；否则 bad_alloc 会逃出 JNI → std::terminate() → SIGABRT。
        ChatMessages history = parseChatHistory(env, jhistory);
        LOGD("Starting stream generation with %zu history messages", history.size());
        return runStreamGenerationWithHistory(env, llmPtr, history, maxTokens, callback);
    } catch (const std::exception& e) {
        LOGE("Exception preparing stream generation: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Exception preparing stream generation: unknown error");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateStreamStructured(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jstring jmessagesJson,
    jstring jtoolsJson,
    jint maxTokens,
    jobject callback) {

    if (llmPtr == 0) return JNI_FALSE;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    // jstringToString 里的 std::string 构造可抛 bad_alloc，纳入 try 保护。
    std::string messagesJson;
    std::string toolsJson;

    try {
        messagesJson = jstringToString(env, jmessagesJson);
        toolsJson = jstringToString(env, jtoolsJson);
        std::string prompt = applyStructuredChatTemplate(llm, messagesJson, toolsJson);
        if (prompt.empty()) {
            LOGE("Failed to apply structured chat template");
            return JNI_FALSE;
        }
        std::vector<int> inputTokens = llm->tokenizer_encode(prompt);
        return runStreamGenerationWithInputIds(env, llmPtr, inputTokens, maxTokens, callback);
    } catch (const std::exception& e) {
        LOGE("Exception preparing structured stream generation: %s", e.what());
        return JNI_FALSE;
    }
}

// =======================
// Cancel Generation
// =======================

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCancel(
    JNIEnv* env, jclass clazz, jlong llmPtr) {
    
    if (llmPtr == 0) return;
    // 用户可能先点停止再切页面触发 release，此时句柄已失效。
    // setCancelFlag 只是往 map 里写一个布尔，本身不会崩，但为保持所有入口
    // 的口径一致（不碰已释放句柄的任何状态），这里同样校验存活。
    if (!acquireLiveLlm(llmPtr)) {
        return;
    }
    
    LOGD("Cancelling generation for LLM at %p", reinterpret_cast<void*>(llmPtr));
    setCancelFlag(llmPtr, true);
    LOGI("Cancellation flag set for LLM");
}

// =======================
// Chat Template
// =======================

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplate(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jstring juserContent) {
    
    if (llmPtr == 0) return nullptr;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        std::string userContent = jstringToString(env, juserContent);
        std::string templated = llm->apply_chat_template(userContent);
        return stringToJstring(env, templated);
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplate: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Exception in applyChatTemplate: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithHistory(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jobject jhistory) {

    if (llmPtr == 0) return nullptr;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        // parseChatHistory 有可抛分配操作，必须在 try 内。
        ChatMessages history = parseChatHistory(env, jhistory);
        std::string templated = llm->apply_chat_template(history);
        return stringToJstring(env, templated);
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplateWithHistory: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Exception in applyChatTemplateWithHistory: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeApplyChatTemplateWithStructuredMessages(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jstring jmessagesJson,
    jstring jtoolsJson) {

    if (llmPtr == 0) return nullptr;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        std::string messagesJson = jstringToString(env, jmessagesJson);
        std::string toolsJson = jstringToString(env, jtoolsJson);
        std::string templated = applyStructuredChatTemplate(llm, messagesJson, toolsJson);
        if (templated.empty()) {
            return nullptr;
        }
        return stringToJstring(env, templated);
    } catch (const std::exception& e) {
        LOGE("Exception in applyChatTemplateWithStructuredMessages: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("Exception in applyChatTemplateWithStructuredMessages: unknown error");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithHistory(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jobject jhistory) {

    if (llmPtr == 0) return 0;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return 0;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        ChatMessages history = parseChatHistory(env, jhistory);
        std::string templated = llm->apply_chat_template(history);
        std::vector<int> tokens = llm->tokenizer_encode(templated);
        return static_cast<jint>(tokens.size());
    } catch (const std::exception& e) {
        LOGE("Exception in countTokensWithHistory: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("Exception in countTokensWithHistory: unknown error");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeCountTokensWithStructuredMessages(
    JNIEnv* env, jclass clazz,
    jlong llmPtr,
    jstring jmessagesJson,
    jstring jtoolsJson) {

    if (llmPtr == 0) return 0;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return 0;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        std::string messagesJson = jstringToString(env, jmessagesJson);
        std::string toolsJson = jstringToString(env, jtoolsJson);
        std::string templated = applyStructuredChatTemplate(llm, messagesJson, toolsJson);
        if (templated.empty()) {
            return 0;
        }
        std::vector<int> tokens = llm->tokenizer_encode(templated);
        return static_cast<jint>(tokens.size());
    } catch (const std::exception& e) {
        LOGE("Exception in countTokensWithStructuredMessages: %s", e.what());
        return 0;
    } catch (...) {
        LOGE("Exception in countTokensWithStructuredMessages: unknown error");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeDumpConfig(
    JNIEnv* env, jclass clazz, jlong llmPtr) {

    if (llmPtr == 0) return nullptr;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        return stringToJstring(env, llm->dump_config());
    } catch (const std::exception& e) {
        LOGE("Exception in dumpConfig: %s", e.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGetContextInfo(
    JNIEnv* env, jclass clazz, jlong llmPtr) {

    if (llmPtr == 0) return nullptr;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return nullptr;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        const LlmContext* context = llm->getContext();
        if (context == nullptr) {
            return nullptr;
        }
        const std::string json = contextToJson(context);
        if (json.empty()) {
            return nullptr;
        }
        return stringToJstring(env, json);
    } catch (const std::exception& e) {
        LOGE("Exception in getContextInfo: %s", e.what());
        return nullptr;
    }
}

// =======================
// Reset
// =======================

extern "C" JNIEXPORT void JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeReset(
    JNIEnv* env, jclass clazz, jlong llmPtr) {
    
    if (llmPtr == 0) return;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    
    try {
        llm->reset();
        LOGD("LLM reset successfully");
    } catch (const std::exception& e) {
        LOGE("Exception in reset: %s", e.what());
    }
}

// =======================
// Set Config
// =======================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetConfig(
    JNIEnv* env, jclass clazz, jlong llmPtr, jstring jconfigJson) {
    
    if (llmPtr == 0) return JNI_FALSE;
    
    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);
    
    try {
        std::string configJson = jstringToString(env, jconfigJson);
        bool success = llm->set_config(configJson);
        if (success) {
            LOGD("LLM config set successfully");
        } else {
            LOGE("Failed to set LLM config");
        }
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Exception in set_config: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Exception in set_config: unknown error");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeSetAudioDataCallback(
    JNIEnv* env, jclass clazz, jlong llmPtr, jobject callback) {

    if (llmPtr == 0) return JNI_FALSE;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    clearAudioCallback(env, llmPtr);

    if (callback == nullptr) {
        // llm->setWavformCallback 内部会构造/销毁 std::function，可能抛 bad_alloc，
        // 必须拦住，否则异常越过 JNI 边界直接 SIGABRT。
        try {
            llm->setWavformCallback(std::function<bool(const float*, size_t, bool)>());
            return JNI_TRUE;
        } catch (const std::exception& e) {
            LOGE("nativeSetAudioDataCallback: clear callback failed: %s", e.what());
            return JNI_FALSE;
        } catch (...) {
            LOGE("nativeSetAudioDataCallback: clear callback failed (unknown)");
            return JNI_FALSE;
        }
    }

    JavaVM* jvm = nullptr;
    if (env->GetJavaVM(&jvm) != JNI_OK || jvm == nullptr) {
        LOGE("Failed to get JavaVM for audio callback");
        return JNI_FALSE;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) {
        LOGE("Failed to get class of audio callback");
        env->ExceptionClear();
        return JNI_FALSE;
    }
    // GetMethodID 失败会挂起 NoSuchMethodError；清理掉再判空返回，
    // 否则挂起的异常会带到下一个 JNI 调用处才爆。
    jmethodID onAudioDataMethod = env->GetMethodID(callbackClass, "onAudioData", "([FZ)Z");
    env->DeleteLocalRef(callbackClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (onAudioDataMethod == nullptr) {
        LOGE("Failed to find onAudioData method in audio callback");
        return JNI_FALSE;
    }

    jobject callbackGlobalRef = env->NewGlobalRef(callback);
    if (callbackGlobalRef == nullptr) {
        LOGE("Failed to create global reference for audio callback");
        return JNI_FALSE;
    }

    try {
        {
            std::lock_guard<std::mutex> lock(gAudioCallbackMutex);
            AudioCallbackHolder holder;
            holder.jvm = jvm;
            holder.callbackGlobalRef = callbackGlobalRef;
            holder.onAudioDataMethod = onAudioDataMethod;
            holder.epoch = gAudioCallbackEpoch.load();
            gAudioCallbacks[llmPtr] = holder;
        }

        llm->setWavformCallback([llmPtr](const float* data, size_t size, bool isLastChunk) {
            return invokeAudioCallback(llmPtr, data, size, isLastChunk);
        });
    } catch (const std::exception& e) {
        // 注册失败必须回收全局引用并撤销表项，否则这里就是一个全局引用泄漏
        // （每次失败的 setAudioDataCallback 都会永久钉住一个 Java 对象）。
        LOGE("nativeSetAudioDataCallback: failed to install callback: %s", e.what());
        clearAudioCallback(env, llmPtr);
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeSetAudioDataCallback: failed to install callback (unknown)");
        clearAudioCallback(env, llmPtr);
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_assistance_mnn_MNNLlmNative_nativeGenerateWavform(
    JNIEnv* env, jclass clazz, jlong llmPtr) {

    if (llmPtr == 0) return JNI_FALSE;

    if (!acquireLiveLlm(llmPtr)) {
        LOGW("stale llm handle 0x%llx (already released), ignoring",
             static_cast<unsigned long long>(llmPtr));
        return JNI_FALSE;
    }
    Llm* llm = reinterpret_cast<Llm*>(llmPtr);

    try {
        llm->generateWavform();
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Exception in generateWavform: %s", e.what());
        return JNI_FALSE;
    }
}

