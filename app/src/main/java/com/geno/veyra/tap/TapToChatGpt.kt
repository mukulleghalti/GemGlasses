package com.geno.veyra.tap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.geno.veyra.R
import org.xmlpull.v1.XmlPullParser

/**
 * Launches ChatGPT's voice mode from a glasses tap.
 *
 * This mirrors Chachan's Android approach: we don't proxy anything and
 * hold no shared API key — we simply open the *user's own* ChatGPT app
 * in voice mode, so the user's own account (free tier or Plus) and its
 * own rate limits apply. Nothing for Veyra to rate-limit.
 *
 * The launch goes through [Intent.ACTION_ASSIST], the framework's
 * "invoke my assistant" intent: Android intercepts it and routes it to
 * the default assistant's voice service. The user sets ChatGPT as the
 * default assistant (Settings → Apps → Default apps → Digital assistant),
 * exactly as Chachan's setup instructs. (ACTION_VOICE_COMMAND was tried
 * first, but it is a legacy plain intent that goes through normal
 * activity resolution — it showed an app chooser that didn't even list
 * ChatGPT — instead of the framework's assistant routing.)
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
                Intent(Intent.ACTION_ASSIST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Log.i(TAG, "ACTION_ASSIST fired")
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

    /**
     * Diagnostic (spike only): dumps the ChatGPT app's static launcher
     * shortcuts — ids and the exact intents they fire — plus any activities
     * with voice/assistant in the name. Reading another app's resources
     * needs no permission. Everything is logged under TAG so it can be
     * copied from LogFox; used to find the real voice-mode entry point
     * after ACTION_ASSIST proved to silently no-op.
     */
    fun dumpShortcuts(context: Context) {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageInfo(CHATGPT_PACKAGE, PackageManager.GET_ACTIVITIES)
            val interesting = pkg.activities
                ?.map { it.name }
                ?.filter {
                    it.contains("voice", ignoreCase = true) ||
                        it.contains("assist", ignoreCase = true)
                }
                .orEmpty()
            if (interesting.isEmpty()) Log.i(TAG, "chatgpt activities: none with voice/assist in the name")
            interesting.forEach { Log.i(TAG, "chatgpt activity: $it") }

            val launch = pm.getLaunchIntentForPackage(CHATGPT_PACKAGE)?.component
            if (launch == null) {
                Log.i(TAG, "chatgpt shortcuts: no launch activity found")
                return
            }
            val ai = pm.getActivityInfo(launch, PackageManager.GET_META_DATA)
            val shortcutsRes = ai.metaData?.getInt("android.app.shortcuts", 0) ?: 0
            if (shortcutsRes == 0) {
                Log.i(TAG, "chatgpt shortcuts: no android.app.shortcuts meta-data on $launch")
                return
            }
            val res = pm.getResourcesForApplication(CHATGPT_PACKAGE)
            val parser = res.getXml(shortcutsRes)
            val ns = "http://schemas.android.com/apk/res/android"
            var currentId: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "shortcut" -> {
                            currentId = parser.getAttributeValue(ns, "shortcutId")
                            Log.i(TAG, "chatgpt shortcut id=$currentId")
                        }
                        "intent" -> Log.i(
                            TAG,
                            "chatgpt shortcut[$currentId] intent " +
                                "action=${parser.getAttributeValue(ns, "action")} " +
                                "data=${parser.getAttributeValue(ns, "data")} " +
                                "targetPackage=${parser.getAttributeValue(ns, "targetPackage")} " +
                                "targetClass=${parser.getAttributeValue(ns, "targetClass")}",
                        )
                        "extra" -> Log.i(
                            TAG,
                            "chatgpt shortcut[$currentId] extra " +
                                "name=${parser.getAttributeValue(ns, "name")} " +
                                "value=${parser.getAttributeValue(ns, "value")}",
                        )
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "shortcut") currentId = null
                }
                event = parser.next()
            }
            Log.i(TAG, "chatgpt shortcut dump done")
        } catch (e: Exception) {
            Log.w(TAG, "chatgpt shortcut dump failed", e)
        }
    }
}
