package com.lpecom.gemglasses.audio

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

    fun routeToGlasses() {

        Log.i(TAG, "========================================")
        Log.i(TAG, "Starting Bluetooth audio routing")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            routeLegacy()
            return
        }

        /*
         * Do not blindly select the first Bluetooth device.
         * First print everything Android exposes as a communication device.
         */
        val devices = audioManager.availableCommunicationDevices

        Log.i(
            TAG,
            "Available communication devices: ${devices.size}"
        )

        devices.forEachIndexed { index, device ->
            Log.i(
                TAG,
                """
                DEVICE #$index
                  type       = ${device.type}
                  product    = ${device.productName}
                  address    = ${device.address}
                  isSource   = ${device.isSource}
                  isSink     = ${device.isSink}
                """.trimIndent()
            )
        }

        /*
         * Ray-Ban Meta microphone normally appears as Bluetooth SCO/HFP.
         *
         * Prefer SCO over BLE headset for the microphone.
         */
        val bluetoothDevice =
            devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
                ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }

        if (bluetoothDevice == null) {

            Log.e(
                TAG,
                "NO BLUETOOTH COMMUNICATION DEVICE FOUND"
            )

            Log.e(
                TAG,
                "Android will use the phone microphone."
            )

            active = true
            return
        }

        Log.i(
            TAG,
            "Selected Bluetooth device:"
        )

        Log.i(
            TAG,
            "  type    = ${bluetoothDevice.type}"
        )

        Log.i(
            TAG,
            "  product = ${bluetoothDevice.productName}"
        )

        Log.i(
            TAG,
            "  address = ${bluetoothDevice.address}"
        )

        /*
         * Meta's examples use MODE_NORMAL when selecting the
         * communication device.
         */
        audioManager.mode = AudioManager.MODE_NORMAL

        val success = audioManager.setCommunicationDevice(bluetoothDevice)

        if (!success) {

            Log.e(
                TAG,
                "setCommunicationDevice() FAILED"
            )

            active = true
            return
        }

        /*
         * Verify what Android actually selected.
         */
        val selected = audioManager.communicationDevice

        Log.i(
            TAG,
            "Android communication device AFTER selection:"
        )

        if (selected != null) {
            Log.i(
                TAG,
                "  type    = ${selected.type}"
            )

            Log.i(
                TAG,
                "  product = ${selected.productName}"
            )

            Log.i(
                TAG,
                "  address = ${selected.address}"
            )
        } else {
            Log.e(
                TAG,
                "communicationDevice is NULL after setCommunicationDevice()"
            )
        }

        /*
         * Now put the audio system into communication mode.
         */
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        active = true

        Log.i(TAG, "Bluetooth audio routing completed")
        Log.i(TAG, "========================================")
    }

    private fun routeLegacy() {

        Log.i(
            TAG,
            "Using legacy Bluetooth SCO routing"
        )

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()

        @Suppress("DEPRECATION")
        audioManager.isBluetoothScoOn = true

        active = true
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
        }

        audioManager.mode = AudioManager.MODE_NORMAL

        active = false

        Log.i(TAG, "Audio routing restored")
    }

    private companion object {
        const val TAG = "BluetoothAudioRouter"
    }
}
