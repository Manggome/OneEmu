package com.manggome.oneemu.emu.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.theme.OneEmuColors

enum class MenuAction { LOAD, SAVE, FAST_FORWARD, CHEATS, SETTINGS, SCREENSHOT, RESET, CLOSE }

/**
 * My Boy-style pause menu: a rounded dark card with a plain vertical list.
 * [fastForwardLabel] is shown as a trailing value on the 빨리감기 row (e.g. "3배 · 켜짐").
 */
@Composable
fun InGameMenuDialog(
    title: String,
    fastForwardLabel: String,
    onAction: (MenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(300.dp),
            shape = RoundedCornerShape(20.dp),
            color = OneEmuColors.Surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = OneEmuColors.OnSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
                )
                MenuRow(stringResource(R.string.menu_load)) { onAction(MenuAction.LOAD) }
                MenuRow(stringResource(R.string.menu_save)) { onAction(MenuAction.SAVE) }
                MenuRow(stringResource(R.string.menu_fast_forward), trailing = fastForwardLabel) { onAction(MenuAction.FAST_FORWARD) }
                MenuRow(stringResource(R.string.menu_cheats)) { onAction(MenuAction.CHEATS) }
                MenuRow(stringResource(R.string.menu_settings)) { onAction(MenuAction.SETTINGS) }
                MenuRow(stringResource(R.string.menu_screenshot)) { onAction(MenuAction.SCREENSHOT) }
                MenuRow(stringResource(R.string.menu_reset)) { onAction(MenuAction.RESET) }
                MenuRow(stringResource(R.string.menu_close)) { onAction(MenuAction.CLOSE) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = OneEmuColors.OnSurface)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = OneEmuColors.Accent)
    }
}
