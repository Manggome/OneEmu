package com.manggome.oneemu.ui.skins

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes

/** Registers Routes.SKINS ("settings/skins/{systemId}") in the main NavHost. */
fun NavGraphBuilder.skinsGraph(nav: NavHostController) {
    composable(
        Routes.SKINS,
        arguments = listOf(navArgument("systemId") { type = NavType.StringType }),
    ) { entry ->
        val system = SystemId.fromId(entry.arguments?.getString("systemId")) ?: SystemId.GBA
        SkinPickerScreen(
            system = system,
            onBack = { nav.popBackStack() },
            onEdit = { nav.navigate(Routes.layoutEditor(system.id)) },
        )
    }
}
