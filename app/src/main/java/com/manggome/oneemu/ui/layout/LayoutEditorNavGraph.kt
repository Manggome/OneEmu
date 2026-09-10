package com.manggome.oneemu.ui.layout

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.R
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes

/** Registers Routes.LAYOUT_EDITOR ("layout/{systemId}") in the main NavHost. */
fun NavGraphBuilder.layoutEditorGraph(nav: NavHostController) {
    composable(
        Routes.LAYOUT_EDITOR,
        arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
    ) { entry ->
        val system = SystemId.fromId(entry.arguments?.getString("systemId")) ?: SystemId.GBA
        LayoutEditorRoute(system, onClose = { nav.popBackStack() })
    }
}

/**
 * Standalone editor screen: dark background with a mock game rectangle and a 세로/가로 toggle that
 * forces the activity orientation while editing so the preview matches what the user will see.
 */
@Composable
fun LayoutEditorRoute(system: SystemId, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val startLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var landscape by rememberSaveable { mutableStateOf(startLandscape) }
    val previous = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    DisposableEffect(landscape) {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = previous }
    }

    // Only render the editor once the window actually has the requested orientation, so the
    // normalized positions are laid out against the right screen shape.
    val actualLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (actualLandscape != landscape) return

    LayoutEditor(
        system = system,
        landscape = landscape,
        showMockGame = true,
        onClose = onClose,
        orientationToggle = {
            Row {
                FilterChip(selected = !landscape, onClick = { landscape = false }, label = { Text(stringResource(R.string.le_portrait)) })
                FilterChip(selected = landscape, onClick = { landscape = true }, label = { Text(stringResource(R.string.le_landscape)) })
            }
        },
    )
}
