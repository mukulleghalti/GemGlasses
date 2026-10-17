package com.geno.veyra.live

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.geno.veyra.glasses.GlassesManager
import com.geno.veyra.glasses.LiveFrame
import com.geno.veyra.settings.SettingsRepository
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.rtmp.rtmp.RtmpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Go Live pipeline (spike): DAT glasses stream (H.264 video + 44.1 kHz
 * PCM mic audio) → AAC encode → RootEncoder RTMP publish to the user's
 * configured destination (YouTube / Twitch / custom).
 *
 * The RTMP connection is only opened once the camera's codec-config
 * frame has supplied SPS/PPS — the client needs them for the FLV
 * headers. Frames arriving before the connection succeeds are dropped.
 */
@Singleton
class LiveStreamManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glasses: GlassesManager,
    private val settings: SettingsRepository,
) : ConnectChecker {

    enum class State { IDLE, CONNECTING, LIVE, ERROR }

    data class Status(
        val state: State = State.IDLE,
        val message: String? = null,
        val startedAtMs: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pumpJob: Job? = null
    private var client: RtmpClient? = null
    private var aac: AacEncoder? = null

    @Volatile
    private var connectStarted = false

    @Volatile
    private var videoCodec: VideoCodec? = null

    private var pendingFirstFrame: LiveFrame.Video? = null
    private var pendingVps: ByteArray? = null
    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null

    @Volatile
    private var rtmpLive = false

    @Volatile
    private var basePtsUs = -1L

    @Volatile
    private var loggedFirstFrame = false

    val isLive: Boolean
        get() = _status.value.state == State.LIVE ||
            _status.value.state == State.CONNECTING

    /**
     * Starts publishing. [url]/[key] default to the saved Live settings.
     * Returns an error message, or null when the start sequence began
     * (watch [status] for LIVE / ERROR).
     */
    suspend fun start(url: String? = null, key: String? = null): String? {
        if (isLive) return "Already live."

        val prefs = settings.preferences.first()
        val baseUrl = (url ?: prefs.liveStreamUrl).trim()
        val streamKey = (key ?: prefs.liveStreamKey).trim()
        if (baseUrl.isEmpty()) {
            return "No stream URL set. Open Go Live and set a destination first."
        }
        val fullUrl = if (streamKey.isEmpty()) {
            baseUrl
        } else {
            baseUrl.trimEnd('/') + "/" + streamKey
        }

        _status.value = Status(State.CONNECTING)
        basePtsUs = -1L
        connectStarted = false
        videoCodec = null
        pendingFirstFrame = null
        pendingVps = null
        pendingSps = null
        pendingPps = null
        rtmpLive = false
        loggedFirstFrame = false

        LiveForegroundService.start(context)

        val rtmp = RtmpClient(this)
        // No setVideoCodec yet: the camera codec is detected from the
        // first parameter sets (the glasses send HEVC, not H264) and
        // set in handlePreConnect, before connect.
        rtmp.setAudioCodec(AudioCodec.AAC)
        rtmp.setAudioInfo(SAMPLE_RATE, false)
        client = rtmp

        aac = AacEncoder(sampleRate = SAMPLE_RATE) { buffer, info ->
            if (rtmpLive) {
                runCatching { client?.sendAudio(buffer, info) }
            }
        }

        pumpJob = scope.launch {
            try {
                glasses.liveFrames().collect { frame ->
                    when (frame) {
                        is LiveFrame.Video -> onVideo(frame, fullUrl)
                        is LiveFrame.Audio -> {
                            if (rtmpLive) {
                                aac?.encode(frame.data, rebased(frame.ptsUs))
                            }
                        }
                    }
                }
                // The camera flow ended without an exception. Never
                // sit on CONNECTING (or LIVE) forever — surface it.
                when (_status.value.state) {
                    State.CONNECTING ->
                        fail("Camera stream ended before going live.")
                    State.LIVE ->
                        fail("Camera stream ended.")
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "frame pump failed", e)
                fail("Camera stream stopped: ${e.message}")
            }
        }
        return null
    }

    private fun onVideo(frame: LiveFrame.Video, fullUrl: String) {
        if (basePtsUs < 0) basePtsUs = frame.ptsUs

        if (!loggedFirstFrame) {
            loggedFirstFrame = true
            Log.i(
                TAG,
                "first video frame: ${frame.width}x${frame.height} " +
                    "compressed=${frame.isCompressed} " +
                    "codecConfig=${frame.isCodecConfig} " +
                    "head=${frame.data.take(8).joinToString(" ") { "%02x".format(it) }}",
            )
        }

        if (!connectStarted) {
            handlePreConnect(frame, fullUrl)
            return
        }

        if (!rtmpLive) return
        if (!frame.isCompressed) return

        sendVideoFrame(frame)
    }

    private fun sendVideoFrame(frame: LiveFrame.Video) {
        // RootEncoder's packetizers treat each buffer as ONE NAL unit
        // (the MediaCodec output shape). DAT frames are whole access
        // units bundling many NAL units; sent unsplit, the bundle is
        // packetized as a single bogus NAL and no decoder can read it
        // (YouTube went live with no picture). Split first. Parameter
        // sets are skipped here — they already travel in the RTMP
        // config record.
        val units = AnnexB.splitUnits(frame.data)
        if (units.isEmpty()) {
            sendUnit(frame.data, frame.ptsUs, wholeFrameKey(frame))
            return
        }
        for (unit in units) {
            val codeLength = AnnexB.startCodeLength(unit)
            if (codeLength == 0 || unit.size <= codeLength) continue
            val header = unit[codeLength].toInt()
            when (videoCodec) {
                VideoCodec.H265 -> {
                    val type = (header shr 1) and 0x3F
                    if (type == 32 || type == 33 || type == 34) continue
                    sendUnit(unit, frame.ptsUs, type == 19 || type == 20)
                }
                else -> {
                    val type = header and 0x1F
                    if (type == 7 || type == 8) continue
                    sendUnit(unit, frame.ptsUs, type == 5)
                }
            }
        }
    }

    private fun sendUnit(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = rebased(ptsUs)
            flags = if (keyFrame) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
        }
        runCatching {
            client?.sendVideo(ByteBuffer.wrap(data), info)
        }
    }

    private fun wholeFrameKey(frame: LiveFrame.Video): Boolean =
        when (videoCodec) {
            VideoCodec.H265 -> H265.isKeyFrame(frame.data)
            else -> H264.isKeyFrame(frame.data)
        }

    /**
     * Pre-connect frame handling. Detects the camera codec from the
     * parameter sets — the glasses send HEVC (VPS/SPS/PPS), older
     * assumptions said H.264 (SPS/PPS) — buffers the sets across
     * frames, and connects RTMP as soon as the set is complete.
     * Frames before connect are dropped; nothing can be sent yet.
     */
    private fun handlePreConnect(frame: LiveFrame.Video, fullUrl: String) {
        if (!frame.isCompressed && !frame.isCodecConfig) return

        val hevc = H265.extractParameterSets(frame.data)
        val h264 = if (hevc.sps == null && hevc.vps == null) {
            H264.extractSpsPps(frame.data)
        } else {
            null
        }

        if (videoCodec == null) {
            val detected = when {
                hevc.sps != null || hevc.vps != null -> VideoCodec.H265
                h264 != null -> VideoCodec.H264
                else -> return
            }
            videoCodec = detected
            Log.i(TAG, "camera codec detected: $detected")
            client?.setVideoCodec(detected)
        }

        when (videoCodec) {
            VideoCodec.H265 -> {
                if (hevc.vps != null) pendingVps = hevc.vps
                if (hevc.sps != null) pendingSps = hevc.sps
                if (hevc.pps != null) pendingPps = hevc.pps
            }
            else -> {
                val pair = h264 ?: H264.extractSpsPps(frame.data)
                if (pair != null) {
                    pendingSps = pair.first
                    pendingPps = pair.second
                }
            }
        }

        // The frame carrying the parameter sets usually also
        // carries the first IDR slice. Stash it and send it once
        // RTMP is live, so the stream starts with a keyframe.
        if (
            hevc.vps != null || hevc.sps != null || hevc.pps != null ||
            h264 != null ||
            H265.isKeyFrame(frame.data) || H264.isKeyFrame(frame.data)
        ) {
            pendingFirstFrame = frame
        }

        val sps = pendingSps ?: return
        val pps = pendingPps ?: return
        val rtmp = client ?: return
        if (videoCodec == VideoCodec.H265) {
            val vps = pendingVps ?: return
            rtmp.setVideoInfo(
                ByteBuffer.wrap(sps),
                ByteBuffer.wrap(pps),
                ByteBuffer.wrap(vps),
            )
        } else {
            rtmp.setVideoInfo(
                ByteBuffer.wrap(sps),
                ByteBuffer.wrap(pps),
                null,
            )
        }
        rtmp.setVideoResolution(frame.width, frame.height)
        connectStarted = true
        Log.i(TAG, "connecting RTMP ($videoCodec)")
        rtmp.connect(fullUrl)
    }

    private fun rebased(ptsUs: Long): Long {
        val base = basePtsUs
        return if (base < 0) ptsUs else maxOf(0L, ptsUs - base)
    }

    /** Stops publishing and releases the camera, encoder and service. */
    fun stop() {
        teardown()
        _status.value = Status(State.IDLE)
    }

    private fun fail(message: String) {
        teardown()
        _status.value = Status(State.ERROR, message)
    }

    private fun teardown() {
        pumpJob?.cancel()
        pumpJob = null
        rtmpLive = false
        connectStarted = false
        runCatching { client?.disconnect() }
        client = null
        aac?.release()
        aac = null
        LiveForegroundService.stop(context)
    }

    // --- ConnectChecker -------------------------------------------------

    override fun onConnectionStarted(url: String) {
        Log.i(TAG, "RTMP connection started: $url")
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "RTMP live")
        rtmpLive = true
        pendingFirstFrame?.let { first ->
            if (first.isCompressed) sendVideoFrame(first)
        }
        pendingFirstFrame = null
        aac?.start()
        _status.value = Status(
            State.LIVE,
            startedAtMs = System.currentTimeMillis(),
        )
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        fail("Connection failed: $reason")
    }

    override fun onDisconnect() {
        Log.i(TAG, "RTMP disconnected")
        if (_status.value.state == State.LIVE) {
            teardown()
            _status.value = Status(State.IDLE)
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error")
        fail("Stream key rejected by the server (auth error).")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "RTMP auth success")
    }

    companion object {
        private const val TAG = "LiveStream"
        private const val SAMPLE_RATE = 44_100
    }
}
