package com.geno.veyra.live

/**
 * Minimal H.264 bitstream helpers for the live pipeline: finding NAL
 * units in the DAT camera stream's frames, which arrive either as
 * Annex B (start-code prefixed) or, for the codec-config frame, possibly
 * as an AVCC record.
 */
object H264 {

    /** NAL unit type of the first unit in [data], or -1 when unknown. */
    fun firstNalType(data: ByteArray): Int {
        if (data.size < 4) return -1
        // Annex B: 00 00 01 or 00 00 00 01, then the NAL header byte.
        if (data[0] == 0.toByte() && data[1] == 0.toByte()) {
            if (data[2] == 1.toByte() && data.size > 3) {
                return data[3].toInt() and 0x1F
            }
            if (data[2] == 0.toByte() && data[3] == 1.toByte() && data.size > 4) {
                return data[4].toInt() and 0x1F
            }
        }
        // AVCC / length-prefixed: 4-byte length, then the NAL header.
        return data[4].toInt() and 0x1F
    }

    fun isKeyFrame(data: ByteArray): Boolean = firstNalType(data) == 5

    /**
     * Extracts (SPS, PPS) from a codec-config frame. Handles Annex B
     * (scans for type 7 / type 8 units) and AVCC records. Returns null
     * when neither is found.
     */
    fun extractSpsPps(config: ByteArray): Pair<ByteArray, ByteArray>? {
        val annexB = splitAnnexB(config)
        if (annexB != null) {
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in annexB) {
                when (nalTypeOf(nal)) {
                    7 -> if (sps == null) sps = nal
                    8 -> if (pps == null) pps = nal
                }
            }
            if (sps != null && pps != null) return sps to pps
        }
        return parseAvcc(config)
    }

    private fun nalTypeOf(nal: ByteArray): Int =
        if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

    /** Splits an Annex B buffer into NAL payloads (start codes stripped). */
    private fun splitAnnexB(data: ByteArray): List<ByteArray>? {
        val starts = mutableListOf<Pair<Int, Int>>() // (payloadStart, codeLength)
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts.add((i + 3) to 3)
                    i += 3
                    continue
                }
                if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts.add((i + 4) to 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return null
        val result = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val from = starts[index].first
            val to = if (index + 1 < starts.size) {
                starts[index + 1].first - starts[index + 1].second
            } else {
                data.size
            }
            if (to > from) result.add(data.copyOfRange(from, to))
        }
        return result
    }

    private fun parseAvcc(data: ByteArray): Pair<ByteArray, ByteArray>? {
        // avcC: [0]=version(1), [4]=0xFC|lengthSizeMinusOne,
        // [5]=0xE0|numSPS, then per SPS: 2-byte length + bytes,
        // then 1-byte numPPS and per PPS: 2-byte length + bytes.
        if (data.size < 7 || data[0] != 1.toByte()) return null
        var offset = 5
        val numSps = data[offset].toInt() and 0x1F
        offset++
        var sps: ByteArray? = null
        repeat(numSps) {
            if (offset + 2 > data.size) return null
            val len = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + len > data.size) return null
            if (sps == null) sps = data.copyOfRange(offset, offset + len)
            offset += len
        }
        if (offset >= data.size) return null
        val numPps = data[offset].toInt() and 0xFF
        offset++
        var pps: ByteArray? = null
        repeat(numPps) {
            if (offset + 2 > data.size) return null
            val len = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (offset + len > data.size) return null
            if (pps == null) pps = data.copyOfRange(offset, offset + len)
            offset += len
        }
        val s = sps ?: return null
        val p = pps ?: return null
        return s to p
    }
}
