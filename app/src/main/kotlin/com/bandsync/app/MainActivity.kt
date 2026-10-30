package com.bandsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bandsync.app.ui.client.ClientScreen
import com.bandsync.app.ui.home.HomeScreen
import com.bandsync.app.ui.master.MasterScreen
import com.bandsync.app.ui.theme.BandSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BandSyncTheme {
                BandSyncNavHost()
            }
        }
    }
}

/** Each session screen is its own destination, so leaving it clears its ViewModel and drops the connection. */
@Composable
private fun BandSyncNavHost() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onHost = { navController.navigate("master") },
                onJoin = { navController.navigate("client") },
            )
        }
        composable("master") {
            MasterScreen(onLeave = { navController.popBackStack() })
        }
        composable("client") {
            ClientScreen(onLeave = { navController.popBackStack() })
        }
    }
}
