package com.lpecom.gemglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.ui.GemGlassesRoot
import com.lpecom.gemglasses.ui.theme.GemGlassesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var glassesManager: GlassesManager

    private val bluetoothPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val scanGranted =
                result[Manifest.permission.BLUETOOTH_SCAN] == true

            val connectGranted =
                result[Manifest.permission.BLUETOOTH_CONNECT] == true

            android.util.Log.i(
                "MainActivity",
                "Bluetooth permission result: " +
                    "SCAN=$scanGranted, CONNECT=$connectGranted"
            )

            if (scanGranted && connectGranted) {

                android.util.Log.i(
                    "MainActivity",
                    "Bluetooth permissions granted"
                )

                initializeGlasses()

            } else {

                android.util.Log.e(
                    "MainActivity",
                    "Bluetooth permissions were not granted"
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        glassesManager.setActivity(this)

        requestBluetoothPermissionsIfNeeded()

        setContent {
            GemGlassesTheme {
                GemGlassesRoot()
            }
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val scanGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

            val connectGranted =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            android.util.Log.i(
                "MainActivity",
                "Current Bluetooth permissions: " +
                    "SCAN=$scanGranted, CONNECT=$connectGranted"
            )

            if (scanGranted && connectGranted) {

                initializeGlasses()

            } else {

                bluetoothPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    )
                )
            }

        } else {

            initializeGlasses()
        }
    }

    private fun initializeGlasses() {

        android.util.Log.i(
            "MainActivity",
            "Initializing glasses backend"
        )

        glassesManager.initialize()
    }

    override fun onDestroy() {

        glassesManager.clearActivity(this)

        super.onDestroy()
    }
}
