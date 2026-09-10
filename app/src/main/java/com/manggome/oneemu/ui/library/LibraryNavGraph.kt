package com.manggome.oneemu.ui.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.manggome.oneemu.ui.Routes

/** Registers the library feature's destinations: Routes.LIBRARY, Routes.GAME, Routes.FOLDERS. */
fun NavGraphBuilder.libraryGraph(nav: NavHostController) {
    composable(Routes.LIBRARY) { LibraryScreen(nav) }
    composable(
        Routes.GAME,
        arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
    ) { entry ->
        val gameId = entry.arguments?.getLong("gameId") ?: -1L
        GameDetailScreen(nav, gameId)
    }
    composable(Routes.FOLDERS) { FoldersScreen(nav) }
}
