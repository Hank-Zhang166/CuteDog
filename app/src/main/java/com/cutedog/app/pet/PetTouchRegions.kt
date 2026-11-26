package com.cutedog.app.pet

import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

internal enum class PetPart(val intent: String?) {
    BODY(null), HEAD("touch_head"), PAW("touch_paw"), TAIL("touch_tail")
}

/** Touch masks are indexed by the exact authored frame drawn, including turns and posture bridges. */
internal class PetTouchRegions(input: InputStream) {
    private val masks: Array<ByteArray>
    private val clips: Map<String, IntArray>
    private val side: Int

    init {
        DataInputStream(GZIPInputStream(input).buffered()).use { data ->
            require(data.readInt() == 0x43445431)
            side = data.readUnsignedByte()
            masks = Array(data.readUnsignedShort()) { ByteArray(data.readUnsignedShort()).also(data::readFully) }
            clips = buildMap {
                repeat(data.readUnsignedShort()) {
                    put(data.readUTF(), IntArray(data.readUnsignedShort()) { data.readUnsignedShort() })
                }
            }
        }
    }

    fun at(clip: String?, frame: Int, u: Float, v: Float): PetPart? {
        if (u !in 0f..<1f || v !in 0f..<1f) return null
        val index = clips[clip]?.getOrNull(frame) ?: return null
        var cell = (v * side).toInt() * side + (u * side).toInt()
        for (byte in masks[index]) {
            val code = byte.toInt() and 255
            cell -= (code and 31) + 1
            if (cell < 0) return when (code ushr 5) {
                1 -> PetPart.BODY
                2 -> PetPart.HEAD
                3 -> PetPart.PAW
                4 -> PetPart.TAIL
                else -> null
            }
        }
        return null
    }
}
