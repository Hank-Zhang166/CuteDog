package com.cutedog.app.voice

import android.content.res.AssetManager
import androidx.annotation.Keep

@Keep
internal object QwenNative {
    init { System.loadLibrary("cutedog_voice") }

    external fun open(assets: AssetManager, talkerPath: ByteArray, codecPath: ByteArray): Long
    external fun render(engine: Long, text: ByteArray, referenceText: ByteArray, speaker: ByteArray,
                        referenceCodes: ByteArray, maxFrames: Int, output: Output): Int
    external fun release(engine: Long)
    external fun tempo(samples: ShortArray, speed: Float): ShortArray?

    @Keep
    interface Output {
        fun stopped(): Boolean
        fun accept(samples: ShortArray): Boolean
    }
}
