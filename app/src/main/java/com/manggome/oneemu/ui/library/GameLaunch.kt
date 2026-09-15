package com.manggome.oneemu.ui.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.emu.EmulatorActivity
import com.manggome.oneemu.library.ArcadeRename
import com.manggome.oneemu.ui.common.ConfirmDialog
import com.manggome.oneemu.ui.common.CoreDownloadDialog
import com.manggome.oneemu.ui.common.InfoDialog
import kotlinx.coroutines.launch

/** Starts the emulator for [game]. Callers run [LibraryViewModel.checkLaunch] first. */
fun launchGame(context: Context, game: GameEntity) {
    context.startActivity(
        Intent(context, EmulatorActivity::class.java).putExtra(EmulatorActivity.EXTRA_GAME_ID, game.id),
    )
}

/**
 * Explains why a game could not be launched (no core, missing BIOS, missing file), offers to download a missing
 * downloadable core ([LaunchCheck.CoreDownload], auto-launches afterwards), or asks before a launch that is known
 * to crash ([LaunchCheck.UnstableWarning]). No-op for [LaunchCheck.Ok].
 */
@Composable
fun LaunchCheckDialog(check: LaunchCheck, onDismiss: () -> Unit) {
    when (check) {
        LaunchCheck.Ok -> Unit
        is LaunchCheck.UnstableWarning -> UnstableLaunchDialog(check, onDismiss)
        is LaunchCheck.NoCore -> InfoDialog(title = stringResource(R.string.lib_launch_no_core_title), onDismiss = onDismiss) {
            Text(
                when {
                    check.neededCore != null -> stringResource(R.string.lib_launch_core_needed, check.neededCore, check.neededMameVersion)
                    check.system != null -> stringResource(R.string.lib_launch_no_core_desc_system, check.system.displayName)
                    else -> stringResource(R.string.lib_launch_no_core_desc)
                },
            )
        }
        is LaunchCheck.ArcadeRename -> ArcadeRenameLaunchDialog(check, onDismiss)
        is LaunchCheck.CoreDownload -> {
            // "이 게임은 MAME 2010 코어가 필요합니다 (약 N MB). 지금 내려받을까요?" — progress in place, launch when installed.
            val context = LocalContext.current
            CoreDownloadDialog(check.core, onDismiss = onDismiss, onInstalled = { launchGame(context, check.game); onDismiss() })
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

/**
 * "이름 바꾸고 실행": renames the zip to the DAT short name the doctor identified by CRC, then starts the emulator.
 * Failures are shown in place (the file is untouched then).
 */
@Composable
private fun ArcadeRenameLaunchDialog(check: LaunchCheck.ArcadeRename, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val suggested = "${check.resolution.report.suggestedName}.zip"
    val current = java.io.File(check.game.path).name
    val failed = error
    if (failed != null) {
        InfoDialog(title = stringResource(R.string.lib_arcade_rename_title), onDismiss = onDismiss) { Text(failed) }
        return
    }
    ConfirmDialog(
        title = stringResource(R.string.lib_launch_rename_title),
        text = stringResource(R.string.lib_launch_rename_desc, current, check.coreName, suggested),
        confirmText = stringResource(R.string.lib_launch_rename_action),
        onConfirm = {
            scope.launch {
                when (val r = ArcadeRename.apply(check.game, check.resolution)) {
                    is ArcadeRename.Result.Done -> { launchGame(context, r.game); onDismiss() }
                    is ArcadeRename.Result.TargetExists -> error = context.getString(R.string.lib_arcade_rename_exists, r.target.name)
                    is ArcadeRename.Result.Failed -> error = context.getString(R.string.lib_arcade_rename_failed, r.target.name)
                    ArcadeRename.Result.SourceMissing -> error = context.getString(R.string.lib_arcade_rename_missing)
                }
            }
        },
        onDismiss = { if (error == null) onDismiss() },
    )
}

/** "강제 종료 위험: … 그래도 실행할까요?" — 실행 starts the emulator anyway, 취소 does nothing. */
@Composable
private fun UnstableLaunchDialog(check: LaunchCheck.UnstableWarning, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ConfirmDialog(
        title = stringResource(R.string.lib_launch_unstable_title),
        text = check.note + "\n\n" + stringResource(R.string.lib_launch_unstable_question),
        confirmText = stringResource(R.string.lib_action_play),
        destructive = true,
        onConfirm = { launchGame(context, check.game); onDismiss() },
        onDismiss = onDismiss,
    )
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
