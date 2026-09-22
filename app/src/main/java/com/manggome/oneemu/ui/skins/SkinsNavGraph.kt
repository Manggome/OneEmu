package com.manggome.oneemu.ui.skins

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.Routes

/** Registers Routes.SKINS ("settings/skins/{profile}") in the main NavHost. */
fun NavGraphBuilder.skinsGraph(nav: NavHostController) {
    composable(
        Routes.SKINS,
        arguments = listOf(navArgument("profile") { type = NavType.StringType }),
    ) { entry ->
        val profile = PadProfile.fromKey(entry.arguments?.getString("profile")) ?: PadProfile(SystemId.GBA)
        SkinPickerScreen(
            profile = profile,
            onBack = { nav.popBackStack() },
            onEdit = { nav.navigate(Routes.layoutEditor(profile.key)) },
        )
    }
}
