package com.manggome.oneemu.ui.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.ui.Routes

/**
 * Registers every settings destination. Wire it from MainActivity.AppNavHost:
 * `settingsGraph(nav)` inside the NavHost builder.
 */
fun NavGraphBuilder.settingsGraph(nav: NavHostController) {
    composable(Routes.SETTINGS) { SettingsHomeScreen(nav) }
    composable(Routes.SETTINGS_VIDEO) { VideoSettingsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.SETTINGS_AUDIO) { AudioSettingsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.SETTINGS_INPUT) { InputSettingsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.SETTINGS_LAYOUTS) {
        LayoutsSettingsScreen(
            onBack = { nav.popBackStack() },
            onEdit = { systemId -> nav.navigate(Routes.layoutEditor(systemId)) },
        )
    }
    composable(Routes.SETTINGS_CORES) {
        CoresSettingsScreen(
            onBack = { nav.popBackStack() },
            onCoreOptions = { coreId -> nav.navigate(Routes.coreOptions(coreId)) },
        )
    }
    composable(Routes.SETTINGS_MISC) { MiscSettingsScreen(onBack = { nav.popBackStack() }) }
    composable(Routes.SETTINGS_ABOUT) { AboutSettingsScreen(onBack = { nav.popBackStack() }) }
    composable(
        Routes.CORE_OPTIONS,
        arguments = listOf(navArgument("coreId") { type = NavType.StringType }),
    ) { entry ->
        val coreId = entry.arguments?.getString("coreId") ?: ""
        CoreOptionsScreen(coreId = coreId, onBack = { nav.popBackStack() })
    }
}
