package com.manggome.oneemu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import com.manggome.oneemu.emu.input.MotionSensors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
    val context = LocalContext.current
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
                // How the remote points. Stored as this game's dolphin_ir_mode, so it sits with the rest
                // of the game's core options.
                val gyro = remember { hasGyroscope(context) }
                var aim by remember(game.id) { mutableStateOf<String?>(null) }
                LaunchedEffect(game.id) { aim = settings.gameOptionOverrides(game.id)[IR_MODE] }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.lib_wii_aim_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    stringResource(if (gyro) R.string.lib_wii_aim_desc else R.string.lib_wii_aim_desc_no_gyro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                // No stored value = the on-screen 조준 stick (EmulatorSession's default).
                val options = buildList {
                    add(null to R.string.lib_wii_aim_stick)
                    if (gyro) add("3" to R.string.lib_wii_aim_gyro)
                    add("2" to R.string.lib_wii_aim_touch)
                }
                options.forEach { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                aim = value
                                scope.launch { settings.setGameOptionOverride(game.id, IR_MODE, value) }
                            }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = aim == value, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (aim == "3") GyroOptions(game.id)
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

private const val IR_MODE = "dolphin_ir_mode"

private fun hasGyroscope(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager)
        ?.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null

/**
 * Where "straight at the screen" is, and how far a turn moves the pointer, for gyro aiming. Nothing is held
 * down while aiming: the pointer follows the phone all the time, measured from the direction it was
 * pointing when the game started (or the last 자이로 가운데 맞추기 / 재조준).
 */
@Composable
private fun GyroOptions(gameId: Long) {
    val settings = OneEmuApp.get().settings
    val scope = rememberCoroutineScope()
    var sensitivity by remember(gameId) { mutableStateOf(100) }
    LaunchedEffect(gameId) {
        sensitivity = settings.gameOptionOverrides(gameId)[MotionSensors.SENSITIVITY_OPTION]?.toIntOrNull() ?: 100
    }
    val autoCenter by settings.observe(MotionSensors.AUTO_CENTER, true).collectAsState(true)

    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(
            stringResource(R.string.lib_wii_gyro_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.lib_wii_gyro_sensitivity), style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in MotionSensors.SENSITIVITIES) {
                FilterChip(
                    selected = v == sensitivity,
                    onClick = {
                        sensitivity = v
                        scope.launch {
                            settings.setGameOptionOverride(gameId, MotionSensors.SENSITIVITY_OPTION, if (v == 100) null else v.toString())
                        }
                    },
                    label = { Text("$v%") },
                )
            }
        }
        Text(
            stringResource(R.string.lib_wii_gyro_sensitivity_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().clickable { scope.launch { settings.set(MotionSensors.AUTO_CENTER, !autoCenter) } }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.lib_wii_gyro_autocenter), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.lib_wii_gyro_autocenter_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoCenter, onCheckedChange = { v -> scope.launch { settings.set(MotionSensors.AUTO_CENTER, v) } })
        }
    }
}
