package com.geno.veyra.live

/**
 * Minimal H.265 (HEVC) bitstream helpers for the live pipeline.
 *
 * The glasses' compressed camera stream is HEVC, not H.264 (proven on
 * device 2026-10-18: the first frame starts with an HEVC VPS,
 * `00 00 00 01 40 01 0c 01 ...`). HEVC NAL units carry a two-byte
 * header, and the parameter sets are VPS (type 32), SPS (33) and
 * PPS (34). Enhanced-RTMP ingest needs all three before the config
 * record can be built.
 */
object H265 {

    /** Parameter sets found in one buffer; any field may be missing. */
    class ParameterSets(
        val vps: ByteArray?,
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    /** HEVC NAL unit type: bits 9..14 of the two-byte NAL header. */
    fun nalType(nal: ByteArray): Int =
        if (nal.size < 2) -1 else (nal[0].toInt() shr 1) and 0x3F

    /** Scans an Annex B buffer for VPS / SPS / PPS units. */
    fun extractParameterSets(data: ByteArray): ParameterSets {
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in splitAnnexB(data)) {
            when (nalType(nal)) {
                32 -> if (vps == null) vps = nal
                33 -> if (sps == null) sps = nal
                34 -> if (pps == null) pps = nal
            }
        }
        return ParameterSets(vps, sps, pps)
    }

    /** True when the buffer holds an IDR slice (type 19 or 20). */
    fun isKeyFrame(data: ByteArray): Boolean =
        splitAnnexB(data).any {
            val type = nalType(it)
            type == 19 || type == 20
        }

    /** Splits an Annex B buffer into NAL payloads (start codes stripped). */
    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
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
        if (starts.isEmpty()) return emptyList()
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
}
