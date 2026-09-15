package com.manggome.oneemu.ui.layout

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
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
import com.manggome.oneemu.emu.ScreenConfig
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes
import com.manggome.oneemu.ui.theme.OneEmuColors

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
 * Standalone editor screen: dark background with the viewport as the mock game rectangle and a
 * 접은 세로/접은 가로/펼친 세로/펼친 가로 selector. Orientation is forced while editing so the preview matches
 * what the user will see; the wide/narrow half of a configuration cannot be forced (it is the physical
 * panel), so editing e.g. 펼친 세로 on a folded phone shows a note that the shape differs.
 */
@Composable
fun LayoutEditorRoute(system: SystemId, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val startConfig = ScreenConfig.from(LocalConfiguration.current)
    var configKey by rememberSaveable { mutableStateOf(startConfig.key) }
    val config = ScreenConfig.fromKey(configKey) ?: startConfig
    val previous = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    DisposableEffect(config.landscape) {
        activity?.requestedOrientation =
            if (config.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = previous }
    }

    // Only render the editor once the window actually has the requested orientation, so the
    // normalized positions are laid out against the right screen shape.
    val actual = LocalConfiguration.current
    val actualLandscape = actual.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (actualLandscape != config.landscape) return
    val wideMismatch = ScreenConfig.isWide(actual) != config.wide

    LayoutEditor(
        system = system,
        config = config,
        showMockGame = true,
        onClose = onClose,
        configSelector = {
            Column {
                ScreenConfigSelector(config, onSelect = { configKey = it.key })
                if (wideMismatch) {
                    Text(
                        stringResource(if (config.wide) R.string.sc_edit_wide_on_narrow else R.string.sc_edit_narrow_on_wide),
                        style = MaterialTheme.typography.labelSmall, color = OneEmuColors.OnSurfaceMuted, maxLines = 1,
                    )
                }
            }
        },
    )
}
