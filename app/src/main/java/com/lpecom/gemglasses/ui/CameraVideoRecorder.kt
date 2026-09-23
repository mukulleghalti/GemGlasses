package com.lpecom.gemglasses.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.meta.wearable.dat.camera.types.VideoFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraVideoRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var muxer: MediaMuxerCompat? = null

    private var trackIndex = -1

    private var recording = false

    private var started = false

    private var width = 0

    private var height = 0

    private var firstTimestampUs = -1L

    private var lastTimestampUs = -1L

    private var pendingCodecConfig: ByteArray? = null

    private var outputUri: Uri? = null

    private var sawKeyFrame = false

    private var receivedFrameCount = 0L

    private var writtenFrameCount = 0L

    private var loggedNalFormat = false

    fun isRecording(): Boolean = recording

    fun start() {

        if (recording) {
            return
        }

        recording = true
        started = false

        trackIndex = -1

        width = 0
        height = 0

        firstTimestampUs = -1L
        lastTimestampUs = -1L

        pendingCodecConfig = null
        outputUri = null

        sawKeyFrame = false

        receivedFrameCount = 0L
        writtenFrameCount = 0L

        loggedNalFormat = false

        muxer = null

        Log.i(
            TAG,
            "Video recording armed",
        )
    }

    fun writeFrame(
        frame: VideoFrame,
    ) {

        if (!recording) {
            return
        }

        if (!frame.isCompressed) {
            Log.w(
                TAG,
                "Ignoring uncompressed frame",
            )
            return
        }

        receivedFrameCount++

        try {

            val bytes =
                readBuffer(frame.buffer)

            if (bytes.isEmpty()) {
                Log.w(
                    TAG,
                    "Received empty compressed frame #$receivedFrameCount",
                )
                return
            }

            if (
                frame.width > 0 &&
                frame.height > 0
            ) {
                width = frame.width
                height = frame.height
            }

            /*
             * -------------------------------------------------
             * EXPLICIT CODEC CONFIG
             * -------------------------------------------------
             *
             * MWDAT normally does NOT provide a separate
             * codec-config frame for this camera stream.
             *
             * Keep support here anyway in case a future SDK
             * provides one.
             */

            if (frame.isCodecConfig) {

                pendingCodecConfig =
                    extractParameterSets(bytes)

                if (
                    pendingCodecConfig == null
                ) {
                    pendingCodecConfig = bytes
                }

                Log.i(
                    TAG,
                    "Received explicit HEVC codec config " +
                        "bytes=${bytes.size} " +
                        "resolution=${frame.width}x${frame.height}",
                )

                return
            }

            /*
             * -------------------------------------------------
             * PARSE ACCESS UNIT
             * -------------------------------------------------
             *
             * Ray-Ban Meta compressed camera frames are HEVC
             * Annex-B access units.
             *
             * IMPORTANT:
             *
             * Do NOT convert these samples to length-prefixed
             * NAL units before passing them to MediaMuxer.
             *
             * MediaMuxer expects the Annex-B representation
             * for this Android/MWDAT camera stream.
             */

            val annexBNalUnits =
                splitAnnexB(bytes)

            if (annexBNalUnits.isNotEmpty()) {

                if (!loggedNalFormat) {

                    Log.i(
                        TAG,
                        "Detected Annex-B HEVC access units " +
                            "bytes=${bytes.size} " +
                            "nalCount=${annexBNalUnits.size}",
                    )

                    logNalTypes(annexBNalUnits)

                    loggedNalFormat = true
                }

                processNalUnits(
                    frame = frame,
                    nalUnits = annexBNalUnits,
                    originalAccessUnit = bytes,
                )

                return
            }

            /*
             * -------------------------------------------------
             * LENGTH-PREFIXED FALLBACK
             * -------------------------------------------------
             *
             * This is only a defensive fallback.
             *
             * MediaMuxer still needs Annex-B samples, so if
             * the source happens to arrive length-prefixed,
             * convert it BACK to Annex-B before writing.
             */

            val lengthPrefixedUnits =
                splitLengthPrefixed(bytes)

            if (lengthPrefixedUnits.isNotEmpty()) {

                if (!loggedNalFormat) {

                    Log.i(
                        TAG,
                        "Detected length-prefixed HEVC access units",
                    )

                    logNalTypes(lengthPrefixedUnits)

                    loggedNalFormat = true
                }

                val annexBAccessUnit =
                    convertNalUnitsToAnnexB(
                        lengthPrefixedUnits,
                    )

                processNalUnits(
                    frame = frame,
                    nalUnits = lengthPrefixedUnits,
                    originalAccessUnit = annexBAccessUnit,
                )

                return
            }

            if (!loggedNalFormat) {

                Log.w(
                    TAG,
                    "Could not parse HEVC frame " +
                        "bytes=${bytes.size} " +
                        "firstBytes=${hexPrefix(bytes)}",
                )

                loggedNalFormat = true
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to write video frame #$receivedFrameCount",
                e,
            )
        }
    }

    private fun processNalUnits(
        frame: VideoFrame,
        nalUnits: List<ByteArray>,
        originalAccessUnit: ByteArray,
    ) {

        if (nalUnits.isEmpty()) {
            return
        }

        /*
         * -------------------------------------------------
         * EXTRACT VPS / SPS / PPS
         * -------------------------------------------------
         *
         * MWDAT sends VPS/SPS/PPS inline in the first keyframe.
         *
         * HEVC:
         *
         * 32 = VPS
         * 33 = SPS
         * 34 = PPS
         */

        val parameterSets =
            extractParameterSetsFromNalUnits(
                nalUnits,
            )

        if (
            parameterSets != null
        ) {

            pendingCodecConfig =
                parameterSets

            Log.d(
                TAG,
                "HEVC parameter sets extracted " +
                    "bytes=${parameterSets.size}",
            )
        }

        val keyFrame =
            containsKeyFrame(
                nalUnits,
            )

        if (keyFrame && !sawKeyFrame) {

            sawKeyFrame = true

            Log.i(
                TAG,
                "HEVC key frame detected " +
                    "timestampUs=${frame.presentationTimeUs} " +
                    "nalTypes=${nalTypes(nalUnits)}",
            )
        }

        /*
         * -------------------------------------------------
         * WAIT FOR PARAMETER SETS + KEYFRAME
         * -------------------------------------------------
         */

        if (!started) {

            val codecConfig =
                pendingCodecConfig

            if (codecConfig == null) {

                if (receivedFrameCount <= 10L) {

                    Log.d(
                        TAG,
                        "Waiting for HEVC VPS/SPS/PPS " +
                            "frame=$receivedFrameCount " +
                            "keyFrame=$keyFrame " +
                            "nalTypes=${nalTypes(nalUnits)}",
                    )
                }

                return
            }

            if (!keyFrame) {

                if (receivedFrameCount <= 10L) {

                    Log.d(
                        TAG,
                        "HEVC config available but waiting for key frame " +
                            "frame=$receivedFrameCount " +
                            "nalTypes=${nalTypes(nalUnits)}",
                    )
                }

                return
            }

            if (
                width <= 0 ||
                height <= 0
            ) {

                Log.w(
                    TAG,
                    "Cannot start recorder without resolution",
                )

                return
            }

            startMuxer(
                codecConfig = codecConfig,
                frame = frame,
            )
        }

        /*
         * -------------------------------------------------
         * WRITE ACCESS UNIT
         * -------------------------------------------------
         *
         * IMPORTANT:
         *
         * Do NOT use:
         *
         * convertAccessUnitToLengthPrefixed(...)
         *
         * here.
         *
         * The Meta glasses HEVC stream is Annex-B and the
         * Annex-B access unit should be passed directly to
         * MediaMuxer.
         */

        if (originalAccessUnit.isEmpty()) {
            return
        }

        val normalizedTimestamp =
            normalizeTimestamp(
                frame.presentationTimeUs,
            )

        val buffer =
            ByteBuffer.wrap(
                originalAccessUnit,
            )

        val flags =
            if (keyFrame) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }

        val info =
            MediaCodec.BufferInfo().apply {
                set(
                    0,
                    originalAccessUnit.size,
                    normalizedTimestamp,
                    flags,
                )
            }

        try {

            muxer?.writeSampleData(
                trackIndex,
                buffer,
                info,
            )

            writtenFrameCount++

            lastTimestampUs =
                frame.presentationTimeUs

            if (
                writtenFrameCount == 1L ||
                writtenFrameCount % 60L == 0L
            ) {

                Log.i(
                    TAG,
                    "HEVC sample written " +
                        "frame=$writtenFrameCount " +
                        "bytes=${originalAccessUnit.size} " +
                        "timestampUs=${frame.presentationTimeUs} " +
                        "keyFrame=$keyFrame",
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "MediaMuxer.writeSampleData failed " +
                    "frame=$writtenFrameCount",
                e,
            )
        }
    }

    private fun startMuxer(
        codecConfig: ByteArray,
        frame: VideoFrame,
    ) {

        Log.i(
            TAG,
            "Starting MP4 muxer " +
                "resolution=${width}x$height " +
                "codecConfigBytes=${codecConfig.size} " +
                "timestampUs=${frame.presentationTimeUs}",
        )

        val format =
            MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_HEVC,
                width,
                height,
            )

        /*
         * Android requires HEVC VPS + SPS + PPS as HEVC
         * codec-specific data in csd-0.
         *
         * The data is stored as Annex-B with:
         *
         * 00 00 00 01 VPS
         * 00 00 00 01 SPS
         * 00 00 00 01 PPS
         */

        format.setByteBuffer(
            "csd-0",
            ByteBuffer.wrap(
                codecConfig,
            ),
        )

        val created =
            createMuxer()

        try {

            muxer =
                created.muxer

            outputUri =
                created.uri

            trackIndex =
                muxer!!.addTrack(
                    format,
                )

            muxer!!.start()

            started = true

            firstTimestampUs =
                frame.presentationTimeUs

            Log.i(
                TAG,
                "MP4 muxer started " +
                    "trackIndex=$trackIndex " +
                    "uri=$outputUri",
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start MP4 muxer",
                e,
            )

            try {
                created.muxer.release()
            } catch (_: Exception) {
            }

            try {
                context.contentResolver.delete(
                    created.uri,
                    null,
                    null,
                )
            } catch (_: Exception) {
            }

            muxer = null
            outputUri = null
            trackIndex = -1

            throw e
        }
    }

    fun stop(): Uri? {

        if (!recording) {
            return outputUri
        }

        recording = false

        val currentMuxer =
            muxer

        val uri =
            outputUri

        muxer = null

        Log.i(
            TAG,
            "Stopping video recording " +
                "receivedFrames=$receivedFrameCount " +
                "writtenFrames=$writtenFrameCount " +
                "started=$started " +
                "uri=$uri",
        )

        var muxerFinalized =
            false

        try {

            if (
                started &&
                currentMuxer != null
            ) {

                currentMuxer.stop()

                muxerFinalized = true

                currentMuxer.release()

            } else {

                currentMuxer?.release()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to finalize video",
                e,
            )

            try {
                currentMuxer?.release()
            } catch (_: Exception) {
            }
        }

        /*
         * Only publish the MediaStore item if MediaMuxer was
         * successfully stopped.
         *
         * If muxer.stop() failed, the MP4 is not trustworthy.
         */

        if (
            uri != null &&
            muxerFinalized
        ) {

            try {

                val values =
                    ContentValues().apply {
                        put(
                            MediaStore.Video.Media.IS_PENDING,
                            0,
                        )
                    }

                context.contentResolver.update(
                    uri,
                    values,
                    null,
                    null,
                )

                Log.i(
                    TAG,
                    "Video published to MediaStore uri=$uri",
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to publish video in MediaStore",
                    e,
                )
            }

        } else if (
            uri != null &&
            started
        ) {

            /*
             * Muxer failed to finalize.
             *
             * Delete the broken pending item instead of
             * leaving an invalid video in Gallery.
             */

            try {

                context.contentResolver.delete(
                    uri,
                    null,
                    null,
                )

                Log.w(
                    TAG,
                    "Deleted video because muxer finalization failed",
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to delete invalid video",
                    e,
                )
            }
        }

        val durationMs =
            if (
                firstTimestampUs >= 0L &&
                lastTimestampUs >= firstTimestampUs
            ) {

                (
                    lastTimestampUs -
                        firstTimestampUs
                    ) / 1_000L

            } else {
                0L
            }

        Log.i(
            TAG,
            "Video recording stopped " +
                "duration=${durationMs}ms " +
                "receivedFrames=$receivedFrameCount " +
                "writtenFrames=$writtenFrameCount " +
                "uri=${if (muxerFinalized) uri else null}",
        )

        started = false
        trackIndex = -1

        pendingCodecConfig = null

        firstTimestampUs = -1L
        lastTimestampUs = -1L

        sawKeyFrame = false

        receivedFrameCount = 0L
        writtenFrameCount = 0L

        return if (muxerFinalized) {
            uri
        } else {
            null
        }
    }

    fun cancel() {

        if (
            !recording &&
            muxer == null
        ) {
            return
        }

        recording = false

        Log.i(
            TAG,
            "Cancelling video recording " +
                "receivedFrames=$receivedFrameCount " +
                "writtenFrames=$writtenFrameCount",
        )

        try {
            muxer?.release()
        } catch (_: Exception) {
        }

        muxer = null

        outputUri?.let { uri ->

            try {

                context.contentResolver.delete(
                    uri,
                    null,
                    null,
                )

            } catch (_: Exception) {
            }
        }

        outputUri = null

        started = false
        trackIndex = -1

        pendingCodecConfig = null

        firstTimestampUs = -1L
        lastTimestampUs = -1L

        sawKeyFrame = false

        receivedFrameCount = 0L
        writtenFrameCount = 0L

        loggedNalFormat = false
    }

    private fun createMuxer(): MuxerCreationResult {

        val resolver =
            context.contentResolver

        val name =
            "GemGlasses_${System.currentTimeMillis()}.mp4"

        val values =
            ContentValues().apply {

                put(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    name,
                )

                put(
                    MediaStore.Video.Media.MIME_TYPE,
                    "video/mp4",
                )

                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/GemGlasses",
                )

                put(
                    MediaStore.Video.Media.IS_PENDING,
                    1,
                )
            }

        val uri =
            resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            )
                ?: throw IllegalStateException(
                    "Could not create MediaStore video",
                )

        try {

            val descriptor =
                resolver.openFileDescriptor(
                    uri,
                    "rw",
                )
                    ?: throw IllegalStateException(
                        "Could not open MediaStore video",
                    )

            val muxer =
                MediaMuxerCompat(
                    descriptor,
                )

            return MuxerCreationResult(
                muxer = muxer,
                uri = uri,
            )

        } catch (e: Exception) {

            resolver.delete(
                uri,
                null,
                null,
            )

            throw e
        }
    }

    private fun normalizeTimestamp(
        timestampUs: Long,
    ): Long {

        if (
            firstTimestampUs < 0L
        ) {
            firstTimestampUs =
                timestampUs
        }

        return maxOf(
            0L,
            timestampUs -
                firstTimestampUs,
        )
    }

    private fun readBuffer(
        source: ByteBuffer,
    ): ByteArray {

        val duplicate =
            source.duplicate()

        val result =
            ByteArray(
                duplicate.remaining(),
            )

        duplicate.get(result)

        return result
    }

    /*
     * ---------------------------------------------------------
     * Annex-B parser
     * ---------------------------------------------------------
     */

    private fun splitAnnexB(
        data: ByteArray,
    ): List<ByteArray> {

        val result =
            mutableListOf<ByteArray>()

        var start =
            findStartCode(
                data,
                0,
            )

        if (start < 0) {
            return emptyList()
        }

        while (start >= 0) {

            val startCodeLength =
                if (
                    start + 3 < data.size &&
                    data[start] == 0.toByte() &&
                    data[start + 1] == 0.toByte() &&
                    data[start + 2] == 1.toByte()
                ) {
                    3
                } else {
                    4
                }

            val nalStart =
                start +
                    startCodeLength

            val nextStart =
                findStartCode(
                    data,
                    nalStart,
                )

            val nalEnd =
                if (nextStart >= 0) {
                    nextStart
                } else {
                    data.size
                }

            if (
                nalEnd >
                nalStart
            ) {

                result.add(
                    data.copyOfRange(
                        nalStart,
                        nalEnd,
                    ),
                )
            }

            start =
                nextStart
        }

        return result
    }

    private fun findStartCode(
        data: ByteArray,
        from: Int,
    ): Int {

        var index =
            from

        while (
            index + 3 <
            data.size
        ) {

            if (
                data[index] ==
                    0.toByte() &&
                data[index + 1] ==
                    0.toByte() &&
                data[index + 2] ==
                    1.toByte()
            ) {

                return index
            }

            if (
                index + 4 <
                data.size &&
                data[index] ==
                    0.toByte() &&
                data[index + 1] ==
                    0.toByte() &&
                data[index + 2] ==
                    0.toByte() &&
                data[index + 3] ==
                    1.toByte()
            ) {

                return index
            }

            index++
        }

        return -1
    }

    /*
     * ---------------------------------------------------------
     * Length-prefixed HEVC fallback
     * ---------------------------------------------------------
     */

    private fun splitLengthPrefixed(
        data: ByteArray,
    ): List<ByteArray> {

        val result =
            mutableListOf<ByteArray>()

        var offset = 0

        while (
            offset + 4 <=
            data.size
        ) {

            val size =
                (
                    ((data[offset].toInt() and 0xFF) shl 24) or
                        ((data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                    )

            if (
                size <= 0 ||
                offset + 4 + size >
                data.size
            ) {
                return emptyList()
            }

            result.add(
                data.copyOfRange(
                    offset + 4,
                    offset + 4 + size,
                ),
            )

            offset +=
                4 +
                    size
        }

        return if (
            offset == data.size
        ) {
            result
        } else {
            emptyList()
        }
    }

    /*
     * ---------------------------------------------------------
     * HEVC helpers
     * ---------------------------------------------------------
     */

    private fun containsKeyFrame(
        nalUnits: List<ByteArray>,
    ): Boolean {

        return nalUnits.any { nal ->

            if (nal.isEmpty()) {
                return@any false
            }

            val nalType =
                (
                    nal[0].toInt() shr 1
                ) and 0x3F

            /*
             * HEVC IRAP pictures:
             *
             * 16 = BLA_W_LP
             * 17 = BLA_W_RADL
             * 18 = BLA_N_LP
             * 19 = IDR_W_RADL
             * 20 = IDR_N_LP
             * 21 = CRA_NUT
             */

            nalType in 16..21
        }
    }

    private fun extractParameterSets(
        data: ByteArray,
    ): ByteArray? {

        val annexB =
            splitAnnexB(data)

        if (annexB.isNotEmpty()) {

            return extractParameterSetsFromNalUnits(
                annexB,
            )
        }

        val lengthPrefixed =
            splitLengthPrefixed(data)

        if (
            lengthPrefixed.isNotEmpty()
        ) {

            return extractParameterSetsFromNalUnits(
                lengthPrefixed,
            )
        }

        return null
    }

    private fun extractParameterSetsFromNalUnits(
        nalUnits: List<ByteArray>,
    ): ByteArray? {

        val parameterSets =
            nalUnits.filter { nal ->

                if (nal.isEmpty()) {
                    false
                } else {

                    val type =
                        (
                            nal[0].toInt() shr 1
                        ) and 0x3F

                    /*
                     * HEVC:
                     *
                     * 32 = VPS
                     * 33 = SPS
                     * 34 = PPS
                     */

                    type == 32 ||
                        type == 33 ||
                        type == 34
                }
            }

        if (
            parameterSets.isEmpty()
        ) {
            return null
        }

        /*
         * Android MediaFormat expects HEVC codec-specific
         * data in csd-0 as VPS + SPS + PPS, each beginning
         * with a 00 00 00 01 start code.
         */

        var totalSize = 0

        for (nal in parameterSets) {

            totalSize +=
                4 +
                    nal.size
        }

        val result =
            ByteArray(
                totalSize,
            )

        var offset = 0

        for (nal in parameterSets) {

            result[offset] =
                0

            result[offset + 1] =
                0

            result[offset + 2] =
                0

            result[offset + 3] =
                1

            System.arraycopy(
                nal,
                0,
                result,
                offset + 4,
                nal.size,
            )

            offset +=
                4 +
                    nal.size
        }

        return result
    }

    /*
     * ---------------------------------------------------------
     * Convert length-prefixed NALs back to Annex-B
     * ---------------------------------------------------------
     *
     * This is only used if a future MWDAT implementation
     * supplies length-prefixed data.
     *
     * The normal Ray-Ban Meta stream should take the
     * originalAccessUnit path and therefore preserve the
     * original bytes untouched.
     */

    private fun convertNalUnitsToAnnexB(
        nalUnits: List<ByteArray>,
    ): ByteArray {

        var totalSize = 0

        for (nal in nalUnits) {
            totalSize +=
                4 +
                    nal.size
        }

        val output =
            ByteArray(
                totalSize,
            )

        var offset = 0

        for (nal in nalUnits) {

            output[offset] =
                0

            output[offset + 1] =
                0

            output[offset + 2] =
                0

            output[offset + 3] =
                1

            System.arraycopy(
                nal,
                0,
                output,
                offset + 4,
                nal.size,
            )

            offset +=
                4 +
                    nal.size
        }

        return output
    }

    private fun nalTypes(
        nalUnits: List<ByteArray>,
    ): String {

        return nalUnits.joinToString(
            separator = ",",
        ) { nal ->

            if (nal.isEmpty()) {
                "-"
            } else {

                (
                    (nal[0].toInt() shr 1) and
                        0x3F
                    ).toString()
            }
        }
    }

    private fun logNalTypes(
        nalUnits: List<ByteArray>,
    ) {

        Log.i(
            TAG,
            "HEVC NAL types=${nalTypes(nalUnits)}",
        )
    }

    private fun hexPrefix(
        bytes: ByteArray,
        count: Int = 16,
    ): String {

        return bytes
            .take(
                minOf(
                    count,
                    bytes.size,
                ),
            )
            .joinToString(
                separator = " ",
            ) {
                "%02X".format(
                    it.toInt() and 0xFF,
                )
            }
    }

    private data class MuxerCreationResult(
        val muxer: MediaMuxerCompat,
        val uri: Uri,
    )

    private companion object {
        const val TAG =
            "CameraVideoRecorder"
    }
}

/*
 * -------------------------------------------------------------
 * MediaMuxer wrapper
 * -------------------------------------------------------------
 */

private class MediaMuxerCompat(
    private val descriptor: android.os.ParcelFileDescriptor,
) {

    private val muxer =
        android.media.MediaMuxer(
            descriptor.fileDescriptor,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

    fun addTrack(
        format: MediaFormat,
    ): Int {
        return muxer.addTrack(
            format,
        )
    }

    fun start() {
        muxer.start()
    }

    fun writeSampleData(
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {

        muxer.writeSampleData(
            trackIndex,
            buffer,
            info,
        )
    }

    fun stop() {

        try {
            muxer.stop()
        } finally {
            descriptor.close()
        }
    }

    fun release() {

        try {
            muxer.release()
        } finally {

            try {
                descriptor.close()
            } catch (_: Exception) {
            }
        }
    }
}
