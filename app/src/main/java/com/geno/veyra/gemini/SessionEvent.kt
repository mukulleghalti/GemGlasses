package com.geno.veyra.gemini

import com.geno.veyra.gemini.protocol.FunctionCall

/**
 * High-level events surfaced by [LiveSession] to the rest of the app. The raw
 * protocol frames stay inside the gemini package; everything above consumes
 * this sealed set.
 */
sealed interface SessionEvent {
    /** Setup acknowledged; the socket is ready to stream audio. */
    data object Ready : SessionEvent

    /** A chunk of assistant audio (PCM 16-bit, 24 kHz, mono) to play back. */
    data class AudioChunk(val pcm: ByteArray) : SessionEvent {
        override fun equals(other: Any?) =
            other is AudioChunk && pcm.contentEquals(other.pcm)
        override fun hashCode() = pcm.contentHashCode()
    }

    /** Incremental transcript text. [fromUser] distinguishes input vs output. */
    data class Transcript(val text: String, val fromUser: Boolean) : SessionEvent

    /** The model asked us to run one or more tools. */
    data class ToolInvocation(val calls: List<FunctionCall>) : SessionEvent

    /** Previously issued tool calls were cancelled (user barged in). */
    data class ToolCancelled(val ids: List<String>) : SessionEvent

    /** Model was interrupted mid-utterance — flush playback immediately. */
    data object Interrupted : SessionEvent

    /** The current assistant turn finished. */
    data object TurnComplete : SessionEvent

    /** Server signalled an imminent disconnect; SessionKeeper should reconnect. */
    data class GoingAway(val timeLeft: String?) : SessionEvent

    /** The socket closed (cleanly or with [error]). */
    data class Closed(val error: Throwable?) : SessionEvent
}
