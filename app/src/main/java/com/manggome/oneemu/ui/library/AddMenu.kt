package com.manggome.oneemu.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.theme.OneEmuColors

/**
 * My Boy-style mint FAB that fans out a small labelled speed-dial menu above it:
 * 게임 추가 / 게임 폴더 추가 / 도움말.
 */
@Composable
fun AddMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAddGame: () -> Unit,
    onAddFolder: () -> Unit,
    onHelp: () -> Unit,
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "fab")

    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SpeedDialItem(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.lib_add_help)) { onExpandedChange(false); onHelp() }
                SpeedDialItem(Icons.Filled.CreateNewFolder, stringResource(R.string.lib_add_folder)) { onExpandedChange(false); onAddFolder() }
                SpeedDialItem(Icons.Filled.VideogameAsset, stringResource(R.string.lib_add_game)) { onExpandedChange(false); onAddGame() }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            containerColor = OneEmuColors.Accent,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(if (expanded) R.string.lib_add_close else R.string.lib_add),
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun SpeedDialItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp,
            onClick = onClick,
        ) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        SmallFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = OneEmuColors.Accent,
            modifier = Modifier.padding(end = 8.dp),
        ) { Icon(icon, contentDescription = label) }
    }
}
