package com.manggome.oneemu.ui

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.manggome.oneemu.ui.layout.layoutEditorGraph
import com.manggome.oneemu.ui.library.libraryGraph
import com.manggome.oneemu.ui.settings.settingsGraph
import com.manggome.oneemu.ui.skins.skinsGraph
import com.manggome.oneemu.ui.theme.OneEmuTheme
import com.manggome.oneemu.update.UpdatePrompt

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenIntent(intent)
    }

    internal fun handleOpenIntent(intent: Intent?) = handleOpenIntentImpl(intent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenIntent(intent)
        setContent {
            OneEmuTheme {
                Surface(Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    AppNavHost(nav)
                    // Checks GitHub Releases occasionally and offers to install a newer APK.
                    UpdatePrompt()
                }
            }
        }
    }
}

private fun MainActivity.handleOpenIntentImpl(intent: Intent?) {
    if (!OpenWithHandler.isOpenIntent(intent)) return
    lifecycleScope.launch { OpenWithHandler.handle(this@handleOpenIntentImpl, intent!!) }
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
        libraryGraph(nav)
        settingsGraph(nav)
        layoutEditorGraph(nav)
        skinsGraph(nav)
    }
}
