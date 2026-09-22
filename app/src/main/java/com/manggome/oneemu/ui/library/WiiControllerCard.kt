package com.manggome.oneemu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.emu.input.WiiController
import com.manggome.oneemu.library.RomInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Which controller Dolphin gives this disc.
 *
 * Only shown for a disc that reads as Wii - or one whose header could not be read, since that is exactly
 * when the automatic choice may be wrong. A GameCube disc has nothing to pick: the core ignores these ids.
 */
@Composable
internal fun WiiControllerCard(game: GameEntity, onEditWiiLayout: () -> Unit) {
    val settings = OneEmuApp.get().settings
    val scope = rememberCoroutineScope()
    val platform by produceState(RomInfo.DiscPlatform.UNKNOWN, game.path) {
        value = withContext(Dispatchers.IO) {
            runCatching { RomInfo.discPlatform(File(game.path)) }.getOrDefault(RomInfo.DiscPlatform.UNKNOWN)
        }
    }
    if (platform == RomInfo.DiscPlatform.GAMECUBE) return

    val storedKey by settings.observe(Settings.Keys.wiiController(game.id), "").collectAsState("")
    val choice = WiiController.fromKey(storedKey)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                stringResource(R.string.lib_wii_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                stringResource(
                    if (platform == RomInfo.DiscPlatform.WII) R.string.lib_wii_desc_wii else R.string.lib_wii_desc_unknown,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            WiiController.entries.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { settings.set(Settings.Keys.wiiController(game.id), option.key) } }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == choice, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (choice.padProfile(platform).isWiimote) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onEditWiiLayout,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(stringResource(R.string.lib_wii_edit_layout))
                }
            }
        }
    }
}
