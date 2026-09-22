package com.lpecom.gemglasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.lpecom.gemglasses.glasses.CameraPermission
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.ui.HomeScreen
import com.lpecom.gemglasses.ui.SettingsScreen
import com.lpecom.gemglasses.ui.TranscriptScreen
import com.lpecom.gemglasses.ui.theme.GemGlassesTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var glassesManager: GlassesManager

    /*
     * ---------------------------------------------------------
     * Bluetooth permissions
     * ---------------------------------------------------------
     */

    private val bluetoothPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->

            val scanGranted =
                result[Manifest.permission.BLUETOOTH_SCAN] == true

            val connectGranted =
                result[Manifest.permission.BLUETOOTH_CONNECT] == true

            Log.i(
                "MainActivity",
                "Bluetooth permission result: " +
                    "SCAN=$scanGranted, CONNECT=$connectGranted"
            )

            if (scanGranted && connectGranted) {

                Log.i(
                    "MainActivity",
                    "Bluetooth permissions granted"
                )

                initializeGlasses()

            } else {

                Log.e(
                    "MainActivity",
                    "Bluetooth permissions were not granted"
                )
            }
        }

    /*
     * ---------------------------------------------------------
     * Meta Wearables CAMERA permission
     * ---------------------------------------------------------
     */

    private val cameraPermissionMutex =
        Mutex()

    private var cameraPermissionContinuation:
        CancellableContinuation<CameraPermission>? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(
            Wearables.RequestPermissionContract()
        ) { result ->

            val status =
                result.getOrDefault(
                    PermissionStatus.Denied
                )

            Log.i(
                "MainActivity",
                "Meta camera permission result = $status"
            )

            val mapped =
                when (status) {

                    PermissionStatus.Granted ->
                        CameraPermission.GRANTED

                    PermissionStatus.Denied ->
                        CameraPermission.DENIED
                }

            cameraPermissionContinuation
                ?.resume(mapped)

            cameraPermissionContinuation = null
        }

    private suspend fun requestMetaCameraPermission():
        CameraPermission {

        return cameraPermissionMutex.withLock {

            suspendCancellableCoroutine { continuation ->

                cameraPermissionContinuation =
                    continuation

                continuation.invokeOnCancellation {
                    cameraPermissionContinuation = null
                }

                Log.i(
                    "MainActivity",
                    "Launching Meta Wearables CAMERA permission"
                )

                cameraPermissionLauncher.launch(
                    Permission.CAMERA
                )
            }
        }
    }

    /*
     * ---------------------------------------------------------
     * Activity lifecycle
     * ---------------------------------------------------------
     */

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        glassesManager.setActivity(this)

        /*
         * Register the permission bridge before any vision
         * request can happen.
         */
        glassesManager.setCameraPermissionRequester {
            requestMetaCameraPermission()
        }

        enableEdgeToEdge()

        requestBluetoothPermissionsIfNeeded()

        setContent {

            GemGlassesTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {

                    GemGlassesRoot()
                }
            }
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val scanGranted =
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED

            val connectGranted =
                checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            Log.i(
                "MainActivity",
                "Current Bluetooth permissions: " +
                    "SCAN=$scanGranted, CONNECT=$connectGranted"
            )

            if (scanGranted && connectGranted) {

                initializeGlasses()

            } else {

                Log.i(
                    "MainActivity",
                    "Requesting Bluetooth permissions before MWDAT initialization"
                )

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

        Log.i(
            "MainActivity",
            "Bluetooth permissions confirmed"
        )

        val app =
            application as GemGlassesApp

        val wearablesReady =
            app.initializeWearables()

        if (!wearablesReady) {

            Log.e(
                "MainActivity",
                "MWDAT SDK initialization failed; " +
                    "not initializing glasses backend"
            )

            return
        }

        Log.i(
            "MainActivity",
            "Initializing glasses backend"
        )

        glassesManager.initialize()
    }

    override fun onDestroy() {

        cameraPermissionContinuation?.cancel()
        cameraPermissionContinuation = null

        glassesManager.clearActivity(this)

        super.onDestroy()
    }
}

/*
 * -------------------------------------------------------------
 * Navigation
 * -------------------------------------------------------------
 */

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon:
        androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun GemGlassesRoot() {

    val navController =
        rememberNavController()

    val items =
        listOf(

            BottomNavItem(
                "home",
                "Home",
                Icons.Default.Home
            ),

            BottomNavItem(
                "transcript",
                "Transcript",
                Icons.AutoMirrored.Filled.List
            ),

            BottomNavItem(
                "settings",
                "Settings",
                Icons.Default.Settings
            ),
        )

    Scaffold(

        bottomBar = {

            NavigationBar {

                val navBackStackEntry by
                    navController.currentBackStackEntryAsState()

                val currentDestination =
                    navBackStackEntry?.destination

                items.forEach { item ->

                    NavigationBarItem(

                        selected =
                            currentDestination
                                ?.hierarchy
                                ?.any {
                                    it.route == item.route
                                } == true,

                        onClick = {

                            navController.navigate(
                                item.route
                            ) {

                                popUpTo(
                                    navController.graph
                                        .findStartDestination()
                                        .id
                                ) {
                                    saveState = true
                                }

                                launchSingleTop = true

                                restoreState = true
                            }
                        },

                        icon = {

                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                            )
                        },

                        label = {
                            Text(item.label)
                        },
                    )
                }
            }
        },

    ) { padding ->

        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
        ) {

            composable("home") {

                HomeScreen(
                    modifier = Modifier
                )
            }

            composable("transcript") {

                TranscriptScreen(
                    modifier = Modifier
                )
            }

            composable("settings") {

                SettingsScreen(
                    modifier = Modifier
                )
            }
        }
    }
}
