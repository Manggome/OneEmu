package com.manggome.oneemu.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.manggome.oneemu.ui.theme.OneEmuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneEmuTheme {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    AppNavHost(nav)
                }
            }
        }
    }
}

/**
 * Main navigation graph. Feature packages register their screens here:
 *  - ui/library  → Routes.LIBRARY, Routes.GAME, Routes.FOLDERS
 *  - ui/settings → Routes.SETTINGS*, Routes.CORE_OPTIONS
 *  - ui/layout   → Routes.LAYOUT_EDITOR
 */
@Composable
fun AppNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) { Placeholder("라이브러리") }
        composable(Routes.SETTINGS) { Placeholder("설정") }
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
