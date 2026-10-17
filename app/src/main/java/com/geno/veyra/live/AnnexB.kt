package com.geno.veyra.live

/**
 * Shared Annex B (start-code) splitting for the live pipeline.
 *
 * RootEncoder's RTMP packetizers treat every buffer as ONE NAL unit —
 * that is the shape MediaCodec encoders emit. DAT camera frames are
 * whole access units bundling many NAL units, so frames are split
 * into single units before they are handed to the packetizer.
 */
object AnnexB {

    /** Start-code length at [offset] in [data] (4 or 3), or 0 when none. */
    fun startCodeLength(data: ByteArray, offset: Int = 0): Int {
        if (
            offset + 4 <= data.size &&
            data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 0.toByte() &&
            data[offset + 3] == 1.toByte()
        ) {
            return 4
        }
        if (
            offset + 3 <= data.size &&
            data[offset] == 0.toByte() &&
            data[offset + 1] == 0.toByte() &&
            data[offset + 2] == 1.toByte()
        ) {
            return 3
        }
        return 0
    }

    /**
     * Splits an Annex B buffer into one chunk per NAL unit. Each chunk
     * KEEPS its start code (the packetizers strip it themselves).
     * Empty when the buffer holds no start code (not Annex B).
     */
    fun splitUnits(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val length = startCodeLength(data, i)
            if (length > 0) {
                starts.add(i)
                i += length
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return emptyList()
        val result = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val from = starts[index]
            val to = if (index + 1 < starts.size) {
                starts[index + 1]
            } else {
                data.size
            }
            if (to > from) result.add(data.copyOfRange(from, to))
        }
        return result
    }
}
