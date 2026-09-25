package com.geno.veyra.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BluetoothAudioRouter @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var active = false

    /**
     * Puts the glasses' microphone in the input chain via the SCO voice
     * channel. Bringing SCO up suspends A2DP, but the caller pins
     * playback to the phone speaker with a media-usage stream, which
     * ignores the communication device — so the two don't fight.
     *
     * Returns true when the glasses' SCO device was found and selected;
     * false keeps the phone mic (no SCO device, selection failed, or
     * pre-Android-12). Never changes Bluetooth adapter state, so the
     * Meta AI app's own BLE link is left alone.
     */
    fun routeMicToGlassesSco(): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.i(
                TAG,
                "SCO mic selection needs Android 12+; keeping the phone mic",
            )
            return false
        }

        val sco = audioManager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        if (sco == null) {
            Log.i(
                TAG,
                "No SCO device found; keeping the phone mic",
            )
            return false
        }

        audioManager.mode = AudioManager.MODE_NORMAL

        if (!audioManager.setCommunicationDevice(sco)) {
            Log.w(
                TAG,
                "setCommunicationDevice(SCO) failed; keeping the phone mic",
            )
            audioManager.mode = AudioManager.MODE_NORMAL
            return false
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        active = true

        Log.i(
            TAG,
            "Glasses mic live via SCO (${sco.productName}); " +
                "playback stays wherever the caller pinned it",
        )
        return true
    }

    /**
     * Preferred output for assistant playback. Playback is always the
     * high-quality music channel (A2DP): while the glasses are connected
     * the voice goes to them, otherwise it falls back to the phone
     * speaker. A null return means "let Android pick" (used when the
     * glasses are connected but no A2DP device is exposed — routing
     * still lands on the glasses in practice).
     */
    fun preferredMediaOutput(
        glassesConnected: Boolean,
    ): AudioDeviceInfo? {

        if (!glassesConnected) {

            Log.i(
                TAG,
                "Glasses not connected; " +
                    "routing assistant audio to the phone speaker",
            )

            return phoneSpeakerOutputDevice()
        }

        val device =
            audioManager.getDevices(
                AudioManager.GET_DEVICES_OUTPUTS,
            ).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }

        if (device == null) {

            Log.w(
                TAG,
                "Glasses connected but no A2DP device found; " +
                    "using the default output",
            )

        } else {

            Log.i(
                TAG,
                "Routing assistant audio to the glasses " +
                    "(A2DP): ${device.productName}",
            )
        }

        return device
    }

    /**
     * The built-in loudspeaker as an output device, so media-usage streams
     * can be forced onto the phone when the glasses aren't connected.
     */
    fun phoneSpeakerOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }

    fun restore() {

        if (!active) return

        Log.i(TAG, "Restoring normal audio routing")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            runCatching {
                audioManager.clearCommunicationDevice()
            }.onFailure {
                Log.w(
                    TAG,
                    "Failed to clear communication device",
                    it
                )
            }

        } else {

            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false

            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()

            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }

        audioManager.mode = AudioManager.MODE_NORMAL

        active = false

        Log.i(TAG, "Audio routing restored")
    }

    private companion object {
        const val TAG = "BluetoothAudioRouter"
    }
}
