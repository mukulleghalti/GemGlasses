package com.geno.veyra.audio

import com.geno.veyra.state.ConversationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mic-mute state for the assistant session.
 *
 * Muting drops outgoing mic audio in [com.geno.veyra.agent.AgentController]
 * without tearing the session down: the user keeps hearing the assistant,
 * the assistant just stops receiving mic input. The session (and its
 * transcript/tools) stays alive, unlike the stop phrase which ends it.
 *
 * A dedicated holder (instead of state on the controller) keeps the
 * `set_mic_muted` tool free of a Dagger cycle: tools are consumed by
 * `ToolRegistry`, which the controller injects.
 */
@Singleton
class MicMuteController @Inject constructor(
    private val conversation: ConversationStore,
) {
    private val _muted = MutableStateFlow(false)

    /** True while mic audio is being dropped. */
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun setMuted(muted: Boolean) {
        if (_muted.value == muted) return
        _muted.value = muted
        conversation.note(
            if (muted) {
                "🔇 Mic muted — the assistant can't hear you. " +
                    "Unmute from the app to talk again."
            } else {
                "🎤 Mic live again."
            },
        )
    }

    /** Back to unmuted; called when a session starts or ends. */
    fun reset() {
        _muted.value = false
    }
}
