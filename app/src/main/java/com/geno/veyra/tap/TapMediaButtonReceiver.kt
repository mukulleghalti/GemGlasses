package com.geno.veyra.tap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Spike: fallback catcher for the glasses' tap.
 *
 * If MediaSessionService doesn't dispatch the media-button event to our
 * active session (e.g. another app wins dispatch), the system falls back
 * to broadcasting [Intent.ACTION_MEDIA_BUTTON]. This receiver catches
 * that broadcast and forwards it to the tap service's handler.
 */
class TapMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            ?: return
        Log.d(
            TAG,
            "receiver got media-button: keyCode=${event.keyCode} " +
                "action=${event.action}",
        )
        // Only handle play/pause-style taps, single key-up.
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            code != KeyEvent.KEYCODE_MEDIA_PLAY &&
            code != KeyEvent.KEYCODE_MEDIA_PAUSE &&
            code != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            return
        }
        if (event.action != KeyEvent.ACTION_UP || event.repeatCount != 0) {
            return
        }
        Log.i(TAG, "receiver: glasses tap detected; launching ChatGPT voice")
        TapToChatGpt.launch(context)
    }

    companion object {
        private const val TAG = "TapToChatGpt"
    }
}
