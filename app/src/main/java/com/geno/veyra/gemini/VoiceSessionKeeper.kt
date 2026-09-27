package com.geno.veyra.gemini

import com.geno.veyra.gemini.protocol.FunctionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/**
 * Provider-agnostic live-voice session: microphone PCM in, transcripts,
 * assistant audio, and tool calls out as [SessionEvent]s.
 *
 * Implemented by [SessionKeeper] (Gemini Live) and by the OpenAI Realtime
 * keeper. [com.geno.veyra.agent.AgentController] picks the implementation
 * from the AI provider chosen in Settings, so everything above this
 * interface (transcript UI, speaker playback, tool dispatch, vision
 * bursts) works unchanged for both backends.
 */
interface VoiceSessionKeeper {

    val events: SharedFlow<SessionEvent>

    fun start(
        scope: CoroutineScope,
        config: SessionConfig,
    )

    fun stop()

    /**
     * Forward microphone PCM (16 kHz mono, 16-bit LE). Implementations
     * resample to whatever their backend expects.
     */
    fun sendAudio(pcm: ByteArray)

    /** Forward a camera JPEG. */
    fun sendFrame(jpeg: ByteArray)

    /** Forward function responses. */
    fun sendToolResponses(responses: List<FunctionResponse>)

    /** Forward user text. */
    fun sendText(text: String)
}
