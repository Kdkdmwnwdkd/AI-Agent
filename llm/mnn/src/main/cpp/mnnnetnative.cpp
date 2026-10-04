//
//  mnnnetnative.cpp
//  MNN
//
//  Created by MNN on 2019/01/29.
//  Copyright © 2018, Alibaba Group Holding Limited
//

#include <android/bitmap.h>
#include <jni.h>
#include <string.h>
#include <MNN/ImageProcess.hpp>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <memory>
#include <string>
#include <vector>
#include <type_traits>

/**
 * 把 Java 字符串安全转成 const char*，并在调用方释放。
 *
 * GetStringUTFChars 在 OOM 时返回 nullptr **并挂起 OutOfMemoryError**。
 * 本文件历史上每次都直接使用返回值（`std::string name = name;`），
 * 一旦为 nullptr 就是 strlen(nullptr) → SIGSEGV。
 *
 * 用法：
 *   const char * s = safeGetUTF(env, jstr);
 *   if (s == nullptr) { /* 已被处理，直接返回 *\/ }
 *   ... 使用 s ...
 *   env->ReleaseStringUTFChars(jstr, s);
 */
static const char * safeGetUTF(JNIEnv * env, jstring jstr) {
    if (jstr == nullptr) return nullptr;
    const char * cstr = env->GetStringUTFChars(jstr, nullptr);
    if (cstr == nullptr) {
        MNN_ERROR("GetStringUTFChars returned null (OOM)\n");
    }
    return cstr;
}

// ==================== 指针校验 ====================
// Java 侧 nativeCreateNetFromFile 在模型文件损坏时合法返回 0，
// Kotlin 若未判 0 直接调用后续接口，native 会对空指针解引用 → SIGSEGV。
// 本文件历史上所有入口都没有做这个校验（对比 mnnmodulennative.cpp 都做了，属漏网）。

static bool requireNet(jlong netPtr, const char * where) {
    if (netPtr == 0) {
        MNN_ERROR("%s: netPtr is null (model may have failed to load)\n", where);
        return false;
    }
    return true;
}

static bool requireNetAndSession(jlong netPtr, jlong sessionPtr, const char * where) {
    if (!requireNet(netPtr, where)) return false;
    if (sessionPtr == 0) {
        MNN_ERROR("%s: sessionPtr is null\n", where);
        return false;
    }
    return true;
}

static bool requireTensor(jlong tensorPtr, const char * where) {
    if (tensorPtr == 0) {
        MNN_ERROR("%s: tensorPtr is null\n", where);
        return false;
    }
    return true;
}

/**
 * JNI 异常屏障（与本项目 llama 侧的同名工具语义一致）。
 *
 * MNN 内部大量使用 STL 与异常，任何 std::bad_alloc / MNN 抛出的异常
 * 若逃出 JNI 边界，C++ 运行时直接 std::terminate() → SIGABRT 杀进程。
 * 本文件历史上完全没有异常处理，是最薄弱的一环。
 *
 * 注意必须区分「有返回值」和「void」两种 body：
 * 若统一写 `return fallback;`，当 decltype(body()) 为 void 时，
 * clang（Android NDK 默认）会以 `-Wreturn-type` 级别的硬错误拒绝编译
 * —— 它不是可忽略的警告。因此下面提供两个重载。
 */
template <typename Fn, typename Fallback>
static auto mnnExceptionBarrier(const char * label, Fallback fallback, Fn && body)
        -> std::enable_if_t<!std::is_void<decltype(body())>::value, decltype(body())> {
    try {
        return body();
    } catch (const std::exception & e) {
        MNN_ERROR("JNI %s threw C++ exception: %s\n", label, e.what());
        return fallback;
    } catch (...) {
        MNN_ERROR("JNI %s threw unknown C++ exception\n", label);
        return fallback;
    }
}

template <typename Fn>
static auto mnnExceptionBarrier(const char * label, Fn && body)
        -> std::enable_if_t<std::is_void<decltype(body())>::value, void> {
    try {
        body();
    } catch (const std::exception & e) {
        MNN_ERROR("JNI %s threw C++ exception: %s\n", label, e.what());
    } catch (...) {
        MNN_ERROR("JNI %s threw unknown C++ exception\n", label);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeCreateNetFromFile(JNIEnv *env, jclass type, jstring modelName_) {
    return mnnExceptionBarrier("nativeCreateNetFromFile", static_cast<jlong>(0), [&]() -> jlong {
        const char *modelName = safeGetUTF(env, modelName_);
        if (modelName == nullptr) {
            return 0;
        }
        MNN::Interpreter * interpreter = nullptr;
        try {
            interpreter = MNN::Interpreter::createFromFile(modelName);
        } catch (...) {
            env->ReleaseStringUTFChars(modelName_, modelName);
            throw;
        }
        env->ReleaseStringUTFChars(modelName_, modelName);

        if (interpreter == nullptr) {
            MNN_ERROR("nativeCreateNetFromFile: createFromFile returned null (bad or missing model)\n");
        }
        return (jlong)interpreter;
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeCreateNetFromBuffer(JNIEnv *env, jclass type, jbyteArray jbuffer) {
    return mnnExceptionBarrier("nativeCreateNetFromBuffer", static_cast<jlong>(0), [&]() -> jlong {
        if (nullptr == jbuffer) {
            return 0;
        }

        auto length = env->GetArrayLength(jbuffer);
        auto destBuffer = env->GetByteArrayElements(jbuffer, nullptr);
        if (destBuffer == nullptr) {
            MNN_ERROR("nativeCreateNetFromBuffer: GetByteArrayElements returned null\n");
            return 0;
        }
        MNN::Interpreter * interpreter = nullptr;
        try {
            interpreter = MNN::Interpreter::createFromBuffer(destBuffer, length);
        } catch (...) {
            env->ReleaseByteArrayElements(jbuffer, destBuffer, 0);
            throw;
        }
        env->ReleaseByteArrayElements(jbuffer, destBuffer, 0);

        return (jlong)interpreter;
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeReleaseNet(JNIEnv *env, jclass type,
                                                                                             jlong netPtr) {
    if (0 == netPtr) {
        return 0;
    }
    // MNN::Interpreter 析构会释放内部 ThreadPool / Backend / Session，
    // 释放路径上仍可能构造临时容器抛 bad_alloc；包一层保证异常不越出 JNI 边界。
    return mnnExceptionBarrier("nativeReleaseNet", static_cast<jlong>(0), [&]() -> jlong {
        delete ((MNN::Interpreter *)netPtr);
        return 0;
    });
}

extern "C" JNIEXPORT jlong JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeCreateSession(
    JNIEnv *env, jclass type, jlong netPtr, jint forwardType, jint numThread, jobjectArray jsaveTensors,
    jobjectArray joutputTensors) {
    return mnnExceptionBarrier("nativeCreateSession", static_cast<jlong>(0), [&]() -> jlong {
        if (!requireNet(netPtr, "nativeCreateSession")) {
            return 0;
        }

        MNN::ScheduleConfig config;
        config.type = (MNNForwardType)forwardType;
        if (numThread > 0) {
            config.numThread = numThread;
        }

        if (jsaveTensors != NULL) {
            int size = env->GetArrayLength(jsaveTensors);
            std::vector<std::string> saveNamesVector;

            for (int i = 0; i < size; i++) {
                jstring jname = (jstring)env->GetObjectArrayElement(jsaveTensors, i);
                if (jname == nullptr) continue;   // 数组元素可能为 null
                const char *name = safeGetUTF(env, jname);
                if (name != nullptr) {
                    saveNamesVector.push_back(std::string(name));
                    env->ReleaseStringUTFChars(jname, name);
                }
                env->DeleteLocalRef(jname);       // 局部引用在循环内必须释放，否则引用表溢出 abort
            }
            config.saveTensors = saveNamesVector;
        }

        if (joutputTensors != NULL) {
            int size = env->GetArrayLength(joutputTensors);
            std::vector<std::string> saveNamesVector;

            for (int i = 0; i < size; i++) {
                jstring jname = (jstring)env->GetObjectArrayElement(joutputTensors, i);
                if (jname == nullptr) continue;
                const char *name = safeGetUTF(env, jname);
                if (name != nullptr) {
                    saveNamesVector.push_back(std::string(name));
                    env->ReleaseStringUTFChars(jname, name);
                }
                env->DeleteLocalRef(jname);
            }

            config.path.outputs = saveNamesVector;
        }

        auto session = ((MNN::Interpreter *)netPtr)->createSession(config);
        return (jlong)session;
    });
}

extern "C" JNIEXPORT void JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeReleaseSession(JNIEnv *env,
                                                                                                jclass type,
                                                                                                jlong netPtr,
                                                                                                jlong sessionPtr) {
    mnnExceptionBarrier("nativeReleaseSession", [&]() -> void {
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeReleaseSession")) return;
        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;
        net->releaseSession(session);

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeRunSession(JNIEnv *env, jclass type,
                                                                                            jlong netPtr,
                                                                                            jlong sessionPtr) {
    return mnnExceptionBarrier("nativeRunSession", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeRunSession")) return JNI_FALSE;
        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;
        return net->runSession(session);

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeRunSessionWithCallback(
    JNIEnv *env, jclass type, jlong netPtr, jlong sessionPtr, jobjectArray nameArray, jlongArray jtensoraddrs) {
    return mnnExceptionBarrier("nativeRunSessionWithCallback", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        // 缺指针校验时 net->runSessionWithCallBack 对空 this 解引用 → SIGSEGV
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeRunSessionWithCallback")) return JNI_FALSE;
        if (nameArray == nullptr || jtensoraddrs == nullptr) {
            MNN_ERROR("nativeRunSessionWithCallback: nameArray or jtensoraddrs is null\n");
            return JNI_FALSE;
        }

        int nameSize   = env->GetArrayLength(nameArray);
        int tensorSize = env->GetArrayLength(jtensoraddrs);
        // 历史上这里只打日志不返回，随后 tensoraddrs[i]（i 可到 nameSize-1）会越界写 —— 堆损坏。
        if (tensorSize < nameSize) {
            MNN_ERROR("tensor array not enough! tensorSize=%d nameSize=%d\n", tensorSize, nameSize);
            return JNI_FALSE;
        }

        jlong *tensoraddrs = (jlong *)env->GetLongArrayElements(jtensoraddrs, nullptr);
        if (tensoraddrs == nullptr) {
            MNN_ERROR("nativeRunSessionWithCallback: GetLongArrayElements returned null\n");
            return JNI_FALSE;
        }

        std::vector<std::string> nameVector;

        for (int i = 0; i < nameSize; i++) {
            jstring jname = (jstring)env->GetObjectArrayElement(nameArray, i);
            if (jname == nullptr) {
                nameVector.push_back(std::string());
                continue;
            }
            const char *name = safeGetUTF(env, jname);
            if (name != nullptr) {
                nameVector.push_back(std::string(name));
                env->ReleaseStringUTFChars(jname, name);
            } else {
                nameVector.push_back(std::string());
            }
            env->DeleteLocalRef(jname);
        }

        MNN::TensorCallBack beforeCallBack = [&](const std::vector<MNN::Tensor *> &ntensors, const std::string &opName) {
            return true;
        };

        MNN::TensorCallBack AfterCallBack = [&](const std::vector<MNN::Tensor *> &ntensors, const std::string &opName) {
            for (int i = 0; i < (int)nameVector.size(); i++) {
                if (nameVector.at(i) == opName && !ntensors.empty()) {
                    auto ntensor = ntensors[0];

                    auto outputTensorUser = new MNN::Tensor(ntensor, MNN::Tensor::TENSORFLOW);
                    ntensor->copyToHostTensor(outputTensorUser);
                    tensoraddrs[i] = (long)outputTensorUser;
                }
            }
            return true;
        };

        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;

        int runResult = net->runSessionWithCallBack(session, beforeCallBack, AfterCallBack, true);

        env->SetLongArrayRegion(jtensoraddrs, 0, tensorSize, tensoraddrs);
        env->ReleaseLongArrayElements(jtensoraddrs, tensoraddrs, 0);

        return runResult;

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeReshapeSession(JNIEnv *env,
                                                                                                jclass type,
                                                                                                jlong netPtr,
                                                                                                jlong sessionPtr) {
    return mnnExceptionBarrier("nativeReshapeSession", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeReshapeSession")) return JNI_FALSE;
        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;
        net->resizeSession(session);
        return 0;

    });
}

extern "C" JNIEXPORT jlong JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeGetSessionInput(
    JNIEnv *env, jclass type, jlong netPtr, jlong sessionPtr, jstring name_) {
    return mnnExceptionBarrier("nativeGetSessionInput", static_cast<jlong>(0), [&]() -> jlong {
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeGetSessionInput")) return 0;
        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;
        if (nullptr == name_) {
            return (jlong)net->getSessionInput(session, nullptr);
        }

        const char *name = safeGetUTF(env, name_);
        if (name == nullptr) return 0;
        auto tensor      = net->getSessionInput(session, name);

        env->ReleaseStringUTFChars(name_, name);
        return (jlong)tensor;

    });
}

extern "C" JNIEXPORT jlong JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeGetSessionOutput(
    JNIEnv *env, jclass type, jlong netPtr, jlong sessionPtr, jstring name_) {
    return mnnExceptionBarrier("nativeGetSessionOutput", static_cast<jlong>(0), [&]() -> jlong {
        if (!requireNetAndSession(netPtr, sessionPtr, "nativeGetSessionOutput")) return 0;
        auto net     = (MNN::Interpreter *)netPtr;
        auto session = (MNN::Session *)sessionPtr;
        if (nullptr == name_) {
            return (jlong)net->getSessionOutput(session, nullptr);
        }
        const char *name = safeGetUTF(env, name_);
        if (name == nullptr) return 0;
        auto tensor      = net->getSessionOutput(session, name);
        env->ReleaseStringUTFChars(name_, name);
        return (jlong)tensor;

    });
}

extern "C" JNIEXPORT void JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeReshapeTensor(JNIEnv *env, jclass type,
                                                                                               jlong netPtr,
                                                                                               jlong tensorPtr,
                                                                                               jintArray dims_) {
    mnnExceptionBarrier("nativeReshapeTensor", [&]() -> void {
        if (!requireNet(netPtr, "nativeReshapeTensor")) return;
        if (!requireTensor(tensorPtr, "nativeReshapeTensor")) return;
        if (dims_ == nullptr) {
            MNN_ERROR("nativeReshapeTensor: dims is null\n");
            return;
        }
        jint *dims   = env->GetIntArrayElements(dims_, NULL);
        if (dims == nullptr) {
            MNN_ERROR("nativeReshapeTensor: GetIntArrayElements returned null\n");
            return;
        }
        auto dimSize = env->GetArrayLength(dims_);
        std::vector<int> dimVector(dimSize);
        for (int i = 0; i < dimSize; ++i) {
            dimVector[i] = dims[i];
        }
        auto net    = (MNN::Interpreter *)netPtr;
        auto tensor = (MNN::Tensor *)tensorPtr;
        net->resizeTensor(tensor, dimVector);
        env->ReleaseIntArrayElements(dims_, dims, 0);

    });
}

extern "C" JNIEXPORT void JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeSetInputIntData(
    JNIEnv *env, jclass type, jlong netPtr, jlong tensorPtr, jintArray data_) {
    mnnExceptionBarrier("nativeSetInputIntData", [&]() -> void {
        if (!requireTensor(tensorPtr, "nativeSetInputIntData")) return;
        if (data_ == nullptr) {
            MNN_ERROR("nativeSetInputIntData: data is null\n");
            return;
        }
        auto tensor = (MNN::Tensor *)tensorPtr;

        jint *data    = env->GetIntArrayElements(data_, NULL);
        if (data == nullptr) {
            MNN_ERROR("nativeSetInputIntData: GetIntArrayElements returned null\n");
            return;
        }
        auto dataSize = env->GetArrayLength(data_);

        // 容量校验：tensor 缓冲区由 shape 决定，Java 数组可能更长。
        // 不校验会直接堆越界写（本文件历史上的真实缺陷）。
        const int capacity = tensor->elementSize();
        const int copyCount = dataSize < capacity ? dataSize : capacity;
        if (copyCount < dataSize) {
            MNN_ERROR("nativeSetInputIntData: data truncated %d -> %d (tensor capacity)\n", dataSize, copyCount);
        }
        for (int i = 0; i < copyCount; ++i) {
            tensor->host<int>()[i] = data[i];
        }

        env->ReleaseIntArrayElements(data_, data, 0);

    });
}

extern "C" JNIEXPORT void JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeSetInputFloatData(
    JNIEnv *env, jclass type, jlong netPtr, jlong tensorPtr, jfloatArray data_) {
    mnnExceptionBarrier("nativeSetInputFloatData", [&]() -> void {
        if (!requireTensor(tensorPtr, "nativeSetInputFloatData")) return;
        if (data_ == nullptr) {
            MNN_ERROR("nativeSetInputFloatData: data is null\n");
            return;
        }
        auto tensor = (MNN::Tensor *)tensorPtr;

        jfloat *data  = env->GetFloatArrayElements(data_, NULL);
        if (data == nullptr) {
            MNN_ERROR("nativeSetInputFloatData: GetFloatArrayElements returned null\n");
            return;
        }
        auto dataSize = env->GetArrayLength(data_);

        // 同 Int 版本：容量校验，防止堆越界写。
        const int capacity = tensor->elementSize();
        const int copyCount = dataSize < capacity ? dataSize : capacity;
        if (copyCount < dataSize) {
            MNN_ERROR("nativeSetInputFloatData: data truncated %d -> %d (tensor capacity)\n", dataSize, copyCount);
        }
        for (int i = 0; i < copyCount; ++i) {
            tensor->host<float>()[i] = data[i];
        }

        env->ReleaseFloatArrayElements(data_, data, 0);

    });
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_ai_assistance_mnn_MNNNetNative_nativeTensorGetDimensions(JNIEnv *env, jclass type, jlong tensorPtr) {
    return mnnExceptionBarrier("nativeTensorGetDimensions", static_cast<jintArray>(nullptr), [&]() -> jintArray {
        if (!requireTensor(tensorPtr, "nativeTensorGetDimensions")) return nullptr;
        auto tensor     = (MNN::Tensor *)tensorPtr;
        auto dimensions = tensor->buffer().dimensions;

        jintArray result = env->NewIntArray(dimensions);
        if (result == nullptr) {
            MNN_ERROR("nativeTensorGetDimensions: NewIntArray returned null\n");
            return nullptr;
        }

        jint *destDims = env->GetIntArrayElements(result, NULL);
        if (destDims == nullptr) return nullptr;
        for (int i = 0; i < dimensions; ++i) {
            destDims[i] = tensor->length(i);
        }
        env->ReleaseIntArrayElements(result, destDims, 0);
        return result;

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeTensorGetUINT8Data(JNIEnv *env,
                                                                                                    jclass type,
                                                                                                    jlong tensorPtr,
                                                                                                    jbyteArray jdest) {
    return mnnExceptionBarrier("nativeTensorGetUINT8Data", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        if (!requireTensor(tensorPtr, "nativeTensorGetUINT8Data")) return 0;
        auto tensor = (MNN::Tensor *)tensorPtr;
        if (nullptr == jdest) {
            return tensor->elementSize();
        }

        auto length = env->GetArrayLength(jdest);
        std::unique_ptr<MNN::Tensor> hostTensor;
        if (tensor->host<int>() == nullptr) {
            // GPU buffer
            hostTensor.reset(new MNN::Tensor(tensor, tensor->getDimensionType(), true));
            tensor->copyToHostTensor(hostTensor.get());
            tensor = hostTensor.get();
        }

        auto size = tensor->elementSize();
        if (length < size) {
            MNN_ERROR("Can't copy buffer, length no enough");
            return JNI_FALSE;
        }

        auto destPtr = env->GetByteArrayElements(jdest, nullptr);
        if (destPtr == nullptr) return JNI_FALSE;
        ::memcpy(destPtr, tensor->host<uint8_t>(), size * sizeof(uint8_t));
        env->ReleaseByteArrayElements(jdest, destPtr, 0);

        return JNI_TRUE;

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeTensorGetIntData(JNIEnv *env,
                                                                                                  jclass type,
                                                                                                  jlong tensorPtr,
                                                                                                  jintArray dest) {
    return mnnExceptionBarrier("nativeTensorGetIntData", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        if (!requireTensor(tensorPtr, "nativeTensorGetIntData")) return 0;
        auto tensor = (MNN::Tensor *)tensorPtr;
        if (nullptr == dest) {
            return tensor->elementSize();
        }

        std::unique_ptr<MNN::Tensor> hostTensor;
        auto length = env->GetArrayLength(dest);
        if (tensor->host<int>() == nullptr) {
            // GPU buffer
            hostTensor.reset(new MNN::Tensor(tensor, tensor->getDimensionType(), true));
            tensor->copyToHostTensor(hostTensor.get());
            tensor = hostTensor.get();
        }

        auto size = tensor->elementSize();
        if (length < size) {
            MNN_ERROR("Can't copy buffer, length no enough");
            return JNI_FALSE;
        }

        auto destPtr = env->GetIntArrayElements(dest, nullptr);
        if (destPtr == nullptr) return JNI_FALSE;
        ::memcpy(destPtr, tensor->host<int>(), size * sizeof(int));
        env->ReleaseIntArrayElements(dest, destPtr, 0);

        return JNI_TRUE;

    });
}

extern "C" JNIEXPORT jint JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeTensorGetData(JNIEnv *env, jclass type,
                                                                                               jlong tensorPtr,
                                                                                               jfloatArray dest) {
    return mnnExceptionBarrier("nativeTensorGetData", static_cast<jint>(JNI_FALSE), [&]() -> jint {
        if (!requireTensor(tensorPtr, "nativeTensorGetData")) return 0;
        auto tensor = reinterpret_cast<MNN::Tensor *>(tensorPtr);
        if (nullptr == dest) {
            std::unique_ptr<MNN::Tensor> hostTensor(new MNN::Tensor(tensor, tensor->getDimensionType(), false));
            return hostTensor->elementSize();
        }
        auto length = env->GetArrayLength(dest);
        std::unique_ptr<MNN::Tensor> hostTensor(new MNN::Tensor(tensor, tensor->getDimensionType(), true));
        tensor->copyToHostTensor(hostTensor.get());
        tensor = hostTensor.get();

        auto size = tensor->elementSize();
        if (length < size) {
            MNN_ERROR("Can't copy buffer, length no enough");
            return JNI_FALSE;
        }
        auto destPtr = env->GetFloatArrayElements(dest, nullptr);
        if (destPtr == nullptr) return JNI_FALSE;
        ::memcpy(destPtr, tensor->host<float>(), size * sizeof(float));
        env->ReleaseFloatArrayElements(dest, destPtr, 0);

        return JNI_TRUE;

    });
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeConvertBufferToTensor(
    JNIEnv *env, jclass type, jbyteArray jbufferData, jint jwidth, jint jheight, jlong tensorPtr, jint srcType,
    jint destFormat, jint filterType, jint wrap, jfloatArray matrixValue_, jfloatArray mean_, jfloatArray normal_) {
    return mnnExceptionBarrier("nativeConvertBufferToTensor", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {

        if (!requireTensor(tensorPtr, "nativeConvertBufferToTensor")) return JNI_FALSE;
        if (jbufferData == nullptr || matrixValue_ == nullptr || mean_ == nullptr || normal_ == nullptr) {
            MNN_ERROR("nativeConvertBufferToTensor: null array argument\n");
            return JNI_FALSE;
        }

        jbyte *bufferData = env->GetByteArrayElements(jbufferData, NULL);
        if (bufferData == NULL) {
            MNN_ERROR("Error Buffer Null!\n");
            return JNI_FALSE;
        }

        {
            auto size = env->GetArrayLength(matrixValue_);
            if (size < 9) {
                env->ReleaseByteArrayElements(jbufferData, bufferData, 0);
                MNN_ERROR("Error matrix length:%d\n", size);
                return JNI_FALSE;
            }
        }

        MNN::CV::ImageProcess::Config config;
        config.destFormat   = (MNN::CV::ImageFormat)destFormat;
        config.sourceFormat = (MNN::CV::ImageFormat)srcType;

        // mean、normal —— 长度必须 >= 3，否则 memcpy 会读越界（历史上未校验）。
        if (env->GetArrayLength(mean_) < 3 || env->GetArrayLength(normal_) < 3) {
            env->ReleaseByteArrayElements(jbufferData, bufferData, 0);
            MNN_ERROR("nativeConvertBufferToTensor: mean/normal must have at least 3 elements\n");
            return JNI_FALSE;
        }
        jfloat *mean   = env->GetFloatArrayElements(mean_, NULL);
        jfloat *normal = env->GetFloatArrayElements(normal_, NULL);
        if (mean == nullptr || normal == nullptr) {
            if (mean != nullptr) env->ReleaseFloatArrayElements(mean_, mean, 0);
            if (normal != nullptr) env->ReleaseFloatArrayElements(normal_, normal, 0);
            env->ReleaseByteArrayElements(jbufferData, bufferData, 0);
            MNN_ERROR("nativeConvertBufferToTensor: GetFloatArrayElements returned null\n");
            return JNI_FALSE;
        }
        ::memcpy(config.mean, mean, 3 * sizeof(float));
        ::memcpy(config.normal, normal, 3 * sizeof(float));
        // filterType、wrap
        config.filterType = (MNN::CV::Filter)filterType;
        config.wrap       = (MNN::CV::Wrap)wrap;
        env->ReleaseFloatArrayElements(mean_, mean, 0);
        env->ReleaseFloatArrayElements(normal_, normal, 0);

        // matrix
        jfloat *matrixValue = env->GetFloatArrayElements(matrixValue_, NULL);
        if (matrixValue == nullptr) {
            env->ReleaseByteArrayElements(jbufferData, bufferData, 0);
            return JNI_FALSE;
        }
        MNN::CV::Matrix transform;
        transform.set9((float *)matrixValue);
        env->ReleaseFloatArrayElements(matrixValue_, matrixValue, 0);

        std::unique_ptr<MNN::CV::ImageProcess> process(MNN::CV::ImageProcess::create(config));
        if (!process) {
            env->ReleaseByteArrayElements(jbufferData, bufferData, 0);
            MNN_ERROR("nativeConvertBufferToTensor: ImageProcess::create returned null\n");
            return JNI_FALSE;
        }
        process->setMatrix(transform);

        auto tensor = (MNN::Tensor *)tensorPtr;

        process->convert((const unsigned char *)bufferData, jwidth, jheight, 0, tensor);
        env->ReleaseByteArrayElements(jbufferData, bufferData, 0);

        return JNI_TRUE;

    });
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_ai_assistance_mnn_MNNNetNative_nativeConvertBitmapToTensor(
    JNIEnv *env, jclass type, jobject srcBitmap, jlong tensorPtr, jint destFormat, jint filterType, jint wrap,
    jfloatArray matrixValue_, jfloatArray mean_, jfloatArray normal_) {
    return mnnExceptionBarrier("nativeConvertBitmapToTensor", static_cast<jboolean>(JNI_FALSE), [&]() -> jboolean {

        if (!requireTensor(tensorPtr, "nativeConvertBitmapToTensor")) return JNI_FALSE;
        if (srcBitmap == nullptr || matrixValue_ == nullptr || mean_ == nullptr || normal_ == nullptr) {
            MNN_ERROR("nativeConvertBitmapToTensor: null argument\n");
            return JNI_FALSE;
        }

        MNN::CV::ImageProcess::Config config;
        config.destFormat = (MNN::CV::ImageFormat)destFormat;

        // AndroidBitmap_getInfo 失败时 bitmapInfo 是未初始化栈变量，直接读 format 会用垃圾值分支。
        AndroidBitmapInfo bitmapInfo;
        int infoRet = AndroidBitmap_getInfo(env, srcBitmap, &bitmapInfo);
        if (infoRet != ANDROID_BITMAP_RESULT_SUCCESS) {
            MNN_ERROR("nativeConvertBitmapToTensor: AndroidBitmap_getInfo failed: %d\n", infoRet);
            return JNI_FALSE;
        }
        switch (bitmapInfo.format) {
            case ANDROID_BITMAP_FORMAT_RGBA_8888:
                config.sourceFormat = MNN::CV::RGBA;
                break;
            case ANDROID_BITMAP_FORMAT_A_8:
                config.sourceFormat = MNN::CV::GRAY;
                break;
            default:
                MNN_ERROR("Don't support bitmap type: %d\n", bitmapInfo.format);
                return JNI_FALSE;
        }

        {
            auto size = env->GetArrayLength(matrixValue_);
            if (size < 9) {
                MNN_ERROR("Error matrix length:%d\n", size);
                return JNI_FALSE;
            }
        }

        // mean、normal 长度校验
        if (env->GetArrayLength(mean_) < 3 || env->GetArrayLength(normal_) < 3) {
            MNN_ERROR("nativeConvertBitmapToTensor: mean/normal must have at least 3 elements\n");
            return JNI_FALSE;
        }
        jfloat *mean   = env->GetFloatArrayElements(mean_, NULL);
        jfloat *normal = env->GetFloatArrayElements(normal_, NULL);
        if (mean == nullptr || normal == nullptr) {
            if (mean != nullptr) env->ReleaseFloatArrayElements(mean_, mean, 0);
            if (normal != nullptr) env->ReleaseFloatArrayElements(normal_, normal, 0);
            MNN_ERROR("nativeConvertBitmapToTensor: GetFloatArrayElements returned null\n");
            return JNI_FALSE;
        }
        ::memcpy(config.mean, mean, 3 * sizeof(float));
        ::memcpy(config.normal, normal, 3 * sizeof(float));
        // filterType、wrap
        config.filterType = (MNN::CV::Filter)filterType;
        config.wrap       = (MNN::CV::Wrap)wrap;
        env->ReleaseFloatArrayElements(mean_, mean, 0);
        env->ReleaseFloatArrayElements(normal_, normal, 0);

        // matrix
        jfloat *matrixValue = env->GetFloatArrayElements(matrixValue_, NULL);
        if (matrixValue == nullptr) return JNI_FALSE;
        MNN::CV::Matrix transform;
        transform.set9((float *)matrixValue);
        env->ReleaseFloatArrayElements(matrixValue_, matrixValue, 0);

        std::unique_ptr<MNN::CV::ImageProcess> process(MNN::CV::ImageProcess::create(config));
        if (!process) {
            MNN_ERROR("nativeConvertBitmapToTensor: ImageProcess::create returned null\n");
            return JNI_FALSE;
        }
        process->setMatrix(transform);

        auto tensor  = (MNN::Tensor *)tensorPtr;
        void *pixels = nullptr;
        // lockPixels 失败时 pixels 仍为 nullptr，直接传给 convert → SIGSEGV。
        int lockRet = AndroidBitmap_lockPixels(env, srcBitmap, &pixels);
        if (lockRet != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
            MNN_ERROR("nativeConvertBitmapToTensor: lockPixels failed: %d\n", lockRet);
            return JNI_FALSE;
        }

        process->convert((const unsigned char *)pixels, bitmapInfo.width, bitmapInfo.height, 0, tensor);
        AndroidBitmap_unlockPixels(env, srcBitmap);
        return JNI_TRUE;

    });
}
