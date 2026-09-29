package com.geno.veyra.tap

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * The launch goes straight to ChatGPT's voice-assistant activity
 * (`com.openai.voice.assistant.AssistantActivity` — the activity behind the
 * app's long-press "Voice" shortcut) via an explicit intent: no
 * assistant-framework round-trip, no chooser. (ACTION_VOICE_COMMAND was
 * tried first and showed a chooser that didn't even list ChatGPT; a bare
 * ACTION_ASSIST resolved to ChatGPT's AssistantProxyActivity but silently
 * no-op'd without the system's voice-interaction context.)
 */
object TapToChatGpt {

    private const val TAG = "TapToChatGpt"
    private const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    /**
     * Voice-assistant activity inside the ChatGPT app, found by dumping its
     * package: this is the activity behind the app's long-press "Voice"
     * shortcut. (The ASSIST proxy,
     * `com.openai.feature.assistant.impl.AssistantProxyActivity`, resolves
     * fine but silently no-ops without the system's voice-interaction
     * context, so we bypass it and go straight to the voice activity.)
     */
    private const val VOICE_ACTIVITY = "com.openai.voice.assistant.AssistantActivity"

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
            // ChatGPT's voice-mode deep link. The dedicated voice activity
            // is not exported and bare ACTION_ASSIST silently no-ops, but
            // ChatGptDeeplinkActivity IS exported and handles
            // https://chatgpt.com/voice (confirmed via its VIEW intent
            // filter on-device).
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/voice"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Log.i(TAG, "voice deep link fired")
        }.onFailure { e ->
            // Never fail silently: a disabled/non-exported activity (or any
            // other launch problem) must be visible, not just a log line.
            Log.w(TAG, "voice deep link launch failed", e)
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
     * Diagnostic (spike only): dumps the ChatGPT app's intent filters for
     * VIEW intents, so we can find the deep-link URL (if any) that opens
     * voice mode. The Voice long-press item is a dynamic shortcut (not in
     * the static XML), and AssistantActivity is not exported, so a deep
     * link into the exported MainActivity is the remaining clean route.
     */
    fun dumpLinkFilters(context: Context) {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            val infos = pm.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")),
                PackageManager.GET_RESOLVED_FILTER,
            )
            if (infos.isEmpty()) Log.i(TAG, "chatgpt link filters: none resolve https://chatgpt.com/")
            infos.filter { it.activityInfo.packageName == CHATGPT_PACKAGE }
                .forEach {
                    Log.i(TAG, "chatgpt link activity: ${it.activityInfo.name} exported=${it.activityInfo.exported}")
                    val f = it.filter
                    if (f == null) {
                        Log.i(TAG, "chatgpt link filter: null")
                    } else {
                        Log.i(TAG, "chatgpt link filter actions: ${f.actionsIterator()?.asSequence()?.toList()}")
                        Log.i(TAG, "chatgpt link filter categories: ${f.categoriesIterator()?.asSequence()?.toList()}")
                        Log.i(TAG, "chatgpt link filter schemes: ${f.schemesIterator()?.asSequence()?.toList()}")
                        Log.i(TAG, "chatgpt link filter authorities: ${f.authoritiesIterator()?.asSequence()?.map { a -> "${a.host}:${a.port}" }?.toList()}")
                        Log.i(TAG, "chatgpt link filter paths: ${f.pathsIterator()?.asSequence()?.map { p -> "${p.path}:${p.type}" }?.toList()}")
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "chatgpt link filter dump failed", e)
        }
    }

    /**
     * Diagnostic (spike only): dumps the ChatGPT app's static launcher
     * shortcuts and any voice/assistant activities with their
     * exported/enabled flags.
     */
    fun dumpShortcuts(context: Context) {
        val pm = context.packageManager
        try {
            @Suppress("DEPRECATION")
            val pkg = pm.getPackageInfo(CHATGPT_PACKAGE, PackageManager.GET_ACTIVITIES)
            val interesting = pkg.activities
                ?.filter {
                    it.name.contains("voice", ignoreCase = true) ||
                        it.name.contains("assist", ignoreCase = true)
                }
                .orEmpty()
            if (interesting.isEmpty()) Log.i(TAG, "chatgpt activities: none with voice/assist in the name")
            interesting.forEach {
                Log.i(TAG, "chatgpt activity: ${it.name} exported=${it.exported} enabled=${it.enabled}")
            }

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
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val attrs = (0 until parser.attributeCount).joinToString(" ") { i ->
                        "${parser.getAttributeName(i)}=${parser.getAttributeValue(i)}"
                    }
                    Log.i(TAG, "chatgpt xml <${parser.name}> $attrs")
                }
                event = parser.next()
            }
            Log.i(TAG, "chatgpt shortcut dump done")
        } catch (e: Exception) {
            Log.w(TAG, "chatgpt shortcut dump failed", e)
        }
    }
}
