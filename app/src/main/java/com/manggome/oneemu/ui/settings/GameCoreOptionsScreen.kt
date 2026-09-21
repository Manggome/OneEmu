package com.manggome.oneemu.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.manggome.oneemu.OneEmuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Core options for one game; the title bar shows the game so it is clear what is being changed. */
@Composable
internal fun GameCoreOptionsScreen(coreId: String, gameId: Long, onBack: () -> Unit) {
    var title by remember { mutableStateOf("") }
    LaunchedEffect(gameId) {
        title = withContext(Dispatchers.IO) { OneEmuApp.get().db.games().get(gameId)?.title.orEmpty() }
    }
    CoreOptionsScreen(coreId = coreId, onBack = onBack, gameId = gameId, gameTitle = title)
}
