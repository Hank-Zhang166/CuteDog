package com.cutedog.app.voice

import android.content.res.AssetManager
import androidx.annotation.Keep

@Keep
internal object QwenNative {
    init {
        System.loadLibrary("cutedog_cpu")
        val library = when (cpuLevel()) {
            2 -> "cutedog_voice_i8mm"
            1 -> "cutedog_voice_fast"
            else -> "cutedog_voice"
        }
        System.loadLibrary(library)
    }
    private external fun cpuLevel(): Int

    external fun open(assets: AssetManager, talkerPath: ByteArray, codecPath: ByteArray): Long
    external fun prime(engine: Long, referenceCodes: ByteArray): Boolean
    external fun render(engine: Long, text: ByteArray, referenceText: ByteArray, speaker: ByteArray,
                        referenceCodes: ByteArray, maxFrames: Int, pace: Float, output: Output): Int
    external fun release(engine: Long)

    @Keep
    interface Output {
        fun stopped(): Boolean
        fun accept(samples: ShortArray): Boolean
    }
}
