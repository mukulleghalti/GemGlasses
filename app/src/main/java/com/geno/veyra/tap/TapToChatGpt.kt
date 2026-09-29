package com.geno.veyra.tap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.geno.veyra.R

/**
 * Launches ChatGPT's voice mode from a glasses tap.
 *
 * This mirrors Chachan's Android approach: we don't proxy anything and
 * hold no shared API key — we simply open the *user's own* ChatGPT app
 * in voice mode, so the user's own account (free tier or Plus) and its
 * own rate limits apply. Nothing for Veyra to rate-limit.
 *
 * The launch goes through [Intent.ACTION_VOICE_COMMAND], which Android
 * routes to the default assistant app. The user sets ChatGPT as the
 * default assistant (Settings → Apps → Default apps → Digital assistant),
 * exactly as Chachan's setup instructs.
 */
object TapToChatGpt {

    private const val TAG = "TapToChatGpt"
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"

    fun isChatGptInstalled(context: Context): Boolean =
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(CHATGPT_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    fun launch(context: Context) {
        if (!isChatGptInstalled(context)) {
            Log.w(TAG, "ChatGPT app not installed; cannot launch voice mode")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.tap_chatgpt_not_installed),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VOICE_COMMAND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Log.i(TAG, "ACTION_VOICE_COMMAND fired")
        }.onFailure { e ->
            // Never fail silently: a missing default assistant (or any
            // other launch problem) must be visible, not just a log line.
            Log.w(TAG, "voice-command launch failed", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    context.getString(R.string.tap_launch_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
