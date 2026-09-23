package com.lpecom.gemglasses.ui

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
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

    private var outputUri: android.net.Uri? = null

    private var sawKeyFrame = false

    fun isRecording(): Boolean = recording

    fun start() {
        if (recording) return

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

        Log.i(TAG, "Video recording armed")
    }

    fun writeFrame(frame: VideoFrame) {
        if (!recording) return
        if (!frame.isCompressed) return

        try {
            val bytes = readBuffer(frame.buffer)

            if (bytes.isEmpty()) return

            /*
             * -------------------------------------------------
             * CODEC CONFIG
             * -------------------------------------------------
             *
             * MWDAT gives us the HEVC codec configuration
             * separately.
             *
             * We retain it until we know the stream resolution.
             */

            if (frame.isCodecConfig) {
                pendingCodecConfig = bytes

                if (width == 0) {
                    width = frame.width
                    height = frame.height
                }

                Log.d(
                    TAG,
                    "Received HEVC codec config " +
                        "bytes=${bytes.size} " +
                        "resolution=${frame.width}x${frame.height}"
                )

                return
            }

            if (frame.width > 0 && frame.height > 0) {
                if (
                    width != frame.width ||
                    height != frame.height
                ) {
                    width = frame.width
                    height = frame.height
                }
            }

            /*
             * -------------------------------------------------
             * WAIT FOR CODEC CONFIG
             * -------------------------------------------------
             */

            val codecConfig = pendingCodecConfig

            if (!started && codecConfig == null) {
                return
            }

            /*
             * -------------------------------------------------
             * PARSE HEVC ACCESS UNIT
             * -------------------------------------------------
             */

            val nalUnits = splitAnnexB(bytes)

            if (nalUnits.isEmpty()) {
                return
            }

            val keyFrame = containsKeyFrame(nalUnits)

            /*
             * Don't start the MP4 until we have an actual
             * decodable key frame.
             */

            if (!started && !keyFrame) {
                return
            }

            /*
             * -------------------------------------------------
             * CREATE MUXER
             * -------------------------------------------------
             */

            if (!started) {
                if (width <= 0 || height <= 0) {
                    Log.w(TAG, "Cannot start recorder without resolution")
                    return
                }

                val config =
                    buildHevcCodecSpecificData(codecConfig ?: bytes)

                if (config.isEmpty()) {
                    Log.w(TAG, "Could not extract HEVC codec configuration")
                    return
                }

                val format =
                    MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_HEVC,
                        width,
                        height,
                    )

                /*
                 * The HEVC parameter sets are supplied as codec
                 * specific data.
                 *
                 * We keep them in Annex-B form because Android's
                 * HEVC MediaFormat accepts codec initialization
                 * data in this form on supported devices.
                 */

                format.setByteBuffer(
                    "csd-0",
                    ByteBuffer.wrap(config),
                )

                val created =
                    createMuxer()

                muxer = created.muxer
                outputUri = created.uri

                trackIndex =
                    muxer!!.addTrack(format)

                muxer!!.start()

                started = true
                sawKeyFrame = true

                firstTimestampUs =
                    frame.presentationTimeUs

                Log.i(
                    TAG,
                    "Video recording started " +
                        "${width}x$height " +
                        "uri=$outputUri"
                )
            }

            /*
             * -------------------------------------------------
             * WRITE SAMPLE
             * -------------------------------------------------
             */

            val normalizedTimestamp =
                normalizeTimestamp(
                    frame.presentationTimeUs,
                )

            val sample =
                convertAccessUnitToLengthPrefixed(
                    nalUnits,
                )

            if (sample.isEmpty()) {
                return
            }

            val buffer =
                ByteBuffer.wrap(sample)

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
                        sample.size,
                        normalizedTimestamp,
                        flags,
                    )
                }

            muxer?.writeSampleData(
                trackIndex,
                buffer,
                info,
            )

            lastTimestampUs =
                frame.presentationTimeUs

        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to write video frame",
                e,
            )
        }
    }

    fun stop(): android.net.Uri? {
        if (!recording) {
            return outputUri
        }

        recording = false

        val currentMuxer = muxer
        val uri = outputUri

        muxer = null

        try {
            if (started && currentMuxer != null) {
                currentMuxer.stop()
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
        }

        if (uri != null) {
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
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to publish video in MediaStore",
                    e,
                )
            }
        }

        val durationMs =
            if (
                firstTimestampUs >= 0 &&
                lastTimestampUs >= firstTimestampUs
            ) {
                (lastTimestampUs - firstTimestampUs) / 1_000L
            } else {
                0L
            }

        Log.i(
            TAG,
            "Video recording stopped " +
                "duration=${durationMs}ms " +
                "uri=$uri"
        )

        started = false
        trackIndex = -1
        pendingCodecConfig = null
        firstTimestampUs = -1L
        lastTimestampUs = -1L
        sawKeyFrame = false

        return uri
    }

    fun cancel() {
        if (!recording && muxer == null) return

        recording = false

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

        Log.i(TAG, "Video recording cancelled")
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
                    "Could not create MediaStore video"
                )

        try {
            val descriptor =
                resolver.openFileDescriptor(
                    uri,
                    "rw",
                )
                    ?: throw IllegalStateException(
                        "Could not open MediaStore video"
                    )

            val muxer =
                MediaMuxerCompat(
                    descriptor,
                    uri,
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
        if (firstTimestampUs < 0L) {
            firstTimestampUs = timestampUs
        }

        return maxOf(
            0L,
            timestampUs - firstTimestampUs,
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
                start + startCodeLength

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

            if (nalEnd > nalStart) {
                result.add(
                    data.copyOfRange(
                        nalStart,
                        nalEnd,
                    )
                )
            }

            start = nextStart
        }

        return result
    }

    private fun findStartCode(
        data: ByteArray,
        from: Int,
    ): Int {

        var index = from

        while (index + 3 < data.size) {

            if (
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 1.toByte()
            ) {
                return index
            }

            if (
                index + 4 < data.size &&
                data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() &&
                data[index + 2] == 0.toByte() &&
                data[index + 3] == 1.toByte()
            ) {
                return index
            }

            index++
        }

        return -1
    }

    private fun containsKeyFrame(
        nalUnits: List<ByteArray>,
    ): Boolean {
        return nalUnits.any { nal ->
            if (nal.isEmpty()) {
                return@any false
            }

            val nalType =
                (nal[0].toInt() shr 1) and 0x3F

            /*
             * HEVC IRAP pictures:
             *
             * 16–21 = IRAP
             *
             * 19/20 are the most common IDR types.
             */

            nalType in 16..21
        }
    }

    private fun buildHevcCodecSpecificData(
        config: ByteArray,
    ): ByteArray {

        val units =
            splitAnnexB(config)

        if (units.isEmpty()) {
            return config
        }

        val parameterSets =
            units.filter { nal ->
                if (nal.isEmpty()) {
                    false
                } else {
                    val type =
                        (nal[0].toInt() shr 1) and 0x3F

                    type == 32 ||
                        type == 33 ||
                        type == 34
                }
            }

        if (parameterSets.isEmpty()) {
            return config
        }

        return parameterSets.fold(
            ByteArray(0),
        ) { accumulator, nal ->
            accumulator + byteArrayOf(
                0,
                0,
                0,
                1,
            ) + nal
        }
    }

    private fun convertAccessUnitToLengthPrefixed(
        nalUnits: List<ByteArray>,
    ): ByteArray {

        var totalSize = 0

        for (nal in nalUnits) {
            totalSize += 4 + nal.size
        }

        val output =
            ByteArray(totalSize)

        var offset = 0

        for (nal in nalUnits) {

            val size =
                nal.size

            output[offset] =
                ((size shr 24) and 0xFF).toByte()

            output[offset + 1] =
                ((size shr 16) and 0xFF).toByte()

            output[offset + 2] =
                ((size shr 8) and 0xFF).toByte()

            output[offset + 3] =
                (size and 0xFF).toByte()

            System.arraycopy(
                nal,
                0,
                output,
                offset + 4,
                size,
            )

            offset += 4 + size
        }

        return output
    }

    private data class MuxerCreationResult(
        val muxer: MediaMuxerCompat,
        val uri: android.net.Uri,
    )

    private companion object {
        const val TAG = "CameraVideoRecorder"
    }
}

/*
 * -------------------------------------------------------------
 * MediaMuxer wrapper
 * -------------------------------------------------------------
 *
 * Kept here so the recorder has a single implementation point.
 */

private class MediaMuxerCompat(
    private val descriptor: android.os.ParcelFileDescriptor,
    private val uri: android.net.Uri,
) {

    private val muxer =
        android.media.MediaMuxer(
            descriptor.fileDescriptor,
            android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )

    fun addTrack(
        format: MediaFormat,
    ): Int {
        return muxer.addTrack(format)
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
