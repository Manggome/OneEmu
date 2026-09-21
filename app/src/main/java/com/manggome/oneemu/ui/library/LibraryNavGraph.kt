package com.manggome.oneemu.ui.library

import androidx.navigation.NavGraphBuilder
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.ui.Routes

/** Registers the library feature's destinations: Routes.LIBRARY, Routes.GAME, Routes.BOXART, Routes.FOLDERS. */
fun NavGraphBuilder.libraryGraph(nav: NavHostController) {
    composable(Routes.LIBRARY) { LibraryScreen(nav) }
    composable(
        Routes.GAME,
        arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
    ) { entry ->
        val gameId = entry.arguments?.getLong("gameId") ?: -1L
        GameDetailScreen(nav, gameId)
    }
    composable(
        Routes.BOXART,
        arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
    ) { entry ->
        BoxArtPickerScreen(gameId = entry.arguments?.getLong("gameId") ?: -1L, vm = viewModel(), onBack = { nav.popBackStack() })
    }
    composable(Routes.FOLDERS) { FoldersScreen(nav) }
}
