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
        rtmpLive = false
        loggedFirstFrame = false

        LiveForegroundService.start(context)

        val rtmp = RtmpClient(this)
        rtmp.setVideoCodec(VideoCodec.H264)
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

        if (frame.isCodecConfig) {
            val spsPps = H264.extractSpsPps(frame.data)
            if (spsPps == null) {
                Log.e(TAG, "codec config: no SPS/PPS found")
                fail("Camera sent a codec config without SPS/PPS.")
                return
            }
            val (sps, pps) = spsPps
            val rtmp = client ?: return
            rtmp.setVideoInfo(
                ByteBuffer.wrap(sps),
                ByteBuffer.wrap(pps),
                null,
            )
            rtmp.setVideoResolution(frame.width, frame.height)
            if (!connectStarted) {
                connectStarted = true
                Log.i(TAG, "connecting RTMP")
                rtmp.connect(fullUrl)
            }
            return
        }

        if (!rtmpLive) return
        if (!frame.isCompressed) return

        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = frame.data.size
            presentationTimeUs = rebased(frame.ptsUs)
            flags = if (H264.isKeyFrame(frame.data)) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
        }
        runCatching {
            client?.sendVideo(ByteBuffer.wrap(frame.data), info)
        }
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
