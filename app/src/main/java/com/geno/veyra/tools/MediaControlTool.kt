package com.geno.veyra.tools

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.geno.veyra.gemini.protocol.FunctionDeclaration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * `media_control` — play/pause/next/previous for whatever audio app is
 * currently active (Spotify, YouTube Music, JioSaavn, podcasts, ...).
 *
 * Works by dispatching media key events through AudioManager, which the
 * system routes to the app holding the active media session. No
 * per-app integration and no special permission needed. It cannot pick
 * a specific app or playlist — it only controls what is playing.
 */
class MediaControlTool @Inject constructor(
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val name = "media_control"

    override val declaration = FunctionDeclaration(
        name = name,
        description = "Controls music/audio playback: play, pause, toggle " +
            "(play/pause), next track, or previous track. Acts on " +
            "whichever audio app is currently active on the phone. Call " +
            "when the user asks to pause, resume, skip, or go back a " +
            "song. It cannot choose an app or a playlist — only transport " +
            "controls for what is playing.",
        parameters = schema(
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["play", "pause", "toggle", "next", "previous"],
                  "description": "Playback action to perform."
                }
              },
              "required": ["action"]
            }
            """,
        ),
    )

    override suspend fun execute(args: JsonObject): JsonObject {
        val action = args.getValue("action").jsonPrimitive.content

        val keyCode = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return buildJsonObject {
                put("status", "error")
                put("error", "unknown action: $action")
            }
        }

        val audioManager =
            context.getSystemService(AudioManager::class.java)
                ?: return buildJsonObject {
                    put("status", "error")
                    put("error", "audio service unavailable")
                }

        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0),
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0),
        )

        return buildJsonObject {
            put("status", "ok")
            put("action", action)
        }
    }
}
