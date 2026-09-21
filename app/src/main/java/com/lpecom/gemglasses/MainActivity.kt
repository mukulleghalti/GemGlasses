package com.lpecom.gemglasses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.lpecom.gemglasses.glasses.real.RealGlassesBackend
import com.lpecom.gemglasses.ui.HomeScreen
import com.lpecom.gemglasses.ui.SettingsScreen
import com.lpecom.gemglasses.ui.TranscriptScreen
import com.lpecom.gemglasses.ui.theme.GemGlassesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var glassesBackend: RealGlassesBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // IMPORTANT:
        // Register the Activity before Compose can create the ViewModel
        // or the user can press "Conectar óculos".
        glassesBackend.setActivity(this)

        enableEdgeToEdge()

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

    override fun onDestroy() {
        glassesBackend.clearActivity(this)
        super.onDestroy()
    }
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
private fun GemGlassesRoot() {
    val navController = rememberNavController()

    val items = listOf(
        BottomNavItem(
            route = "home",
            label = "Home",
            icon = Icons.Default.Home,
        ),
        BottomNavItem(
            route = "transcript",
            label = "Transcript",
            icon = Icons.AutoMirrored.Filled.List,
        ),
        BottomNavItem(
            route = "settings",
            label = "Settings",
            icon = Icons.Default.Settings,
        ),
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == item.route } == true,
                        onClick = {
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
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
                    modifier = Modifier,
                )
            }

            composable("transcript") {
                TranscriptScreen(
                    modifier = Modifier,
                )
            }

            composable("settings") {
                SettingsScreen(
                    modifier = Modifier,
                )
            }
        }
    }
}
