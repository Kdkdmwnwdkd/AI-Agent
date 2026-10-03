package com.k2fsa.sherpa.mnn

class OnlineStream(var ptr: Long = 0) {
    /**
     * native 边界防线：ptr 为 0 表示句柄无效（创建失败或已释放）。
     * 把 0 传进 JNI 会让 native 解引用空指针 → SIGSEGV 闪退，Kotlin try/catch 拦不住。
     * 因此所有对外调用前先断言句柄有效。
     */
    private fun requireValid() {
        check(ptr != 0L) { "OnlineStream 句柄无效（创建失败或已释放）" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        requireValid()
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        requireValid()
        inputFinished(ptr)
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)


    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

