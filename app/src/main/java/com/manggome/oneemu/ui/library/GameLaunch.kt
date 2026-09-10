package com.manggome.oneemu.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.emu.EmulatorActivity
import com.manggome.oneemu.ui.common.InfoDialog

/** Starts the emulator for [game]. Callers run [LibraryViewModel.checkLaunch] first. */
fun launchGame(context: Context, game: GameEntity) {
    context.startActivity(
        Intent(context, EmulatorActivity::class.java).putExtra(EmulatorActivity.EXTRA_GAME_ID, game.id),
    )
}

/** Explains why a game could not be launched (no core, missing BIOS, missing file). No-op for [LaunchCheck.Ok]. */
@Composable
fun LaunchCheckDialog(check: LaunchCheck, onDismiss: () -> Unit) {
    when (check) {
        LaunchCheck.Ok -> Unit
        is LaunchCheck.NoCore -> InfoDialog(title = stringResource(R.string.lib_launch_no_core_title), onDismiss = onDismiss) {
            Text(
                if (check.system != null) stringResource(R.string.lib_launch_no_core_desc_system, check.system.displayName)
                else stringResource(R.string.lib_launch_no_core_desc),
            )
        }
        is LaunchCheck.MissingBios -> InfoDialog(title = stringResource(R.string.lib_launch_bios_title), onDismiss = onDismiss) {
            Column {
                Text(stringResource(R.string.lib_launch_bios_desc))
                Spacer(Modifier.height(12.dp))
                PathText(check.dir.absolutePath)
                Spacer(Modifier.height(12.dp))
                for (b in check.files) {
                    Text("• ${b.file}", fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    if (b.description.isNotBlank()) {
                        Text(b.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(check.core.displayName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is LaunchCheck.MissingFile -> InfoDialog(title = stringResource(R.string.lib_launch_missing_file_title), onDismiss = onDismiss) {
            Text(stringResource(R.string.lib_launch_missing_file_desc, check.path))
        }
    }
}

/** Monospace, selectable-looking path block used by the BIOS and help dialogs. */
@Composable
fun PathText(path: String) {
    Text(
        text = path,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.primary,
    )
}
