package com.manggome.oneemu.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.launch

@Composable
internal fun LayoutsSettingsScreen(onBack: () -> Unit, onEdit: (systemId: String) -> Unit, onSkins: (systemId: String) -> Unit = {}) {
    val context = LocalContext.current
    val settings = OneEmuApp.get().settings
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_layouts), onBack = onBack) {
        NoteText(stringResource(R.string.layouts_edit_hint))
        SystemId.ordered.forEach { system ->
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(system.id) },
                headlineContent = { Text(system.displayName) },
                supportingContent = { Text(system.shortName, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                leadingContent = { Box(Modifier.size(22.dp).background(system.color, CircleShape)) },
                trailingContent = {
                    // Pad skin picker lives in ui/skins; we only navigate there.
                    TextButton(onClick = { onSkins(system.id) }) {
                        Icon(Icons.Outlined.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.layouts_pad_skin))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.layouts_reset_all),
            subtitle = stringResource(R.string.layouts_reset_all_desc),
            icon = Icons.Outlined.RestartAlt,
            onClick = { confirmReset = true },
        )
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.layouts_reset_confirm_title),
            body = stringResource(R.string.layouts_reset_confirm_body),
            confirmLabel = stringResource(R.string.reset),
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                scope.launch {
                    for (system in SystemId.entries) {
                        settings.remove(Settings.Keys.layout(system.id, landscape = true))
                        settings.remove(Settings.Keys.layout(system.id, landscape = false))
                    }
                    Toast.makeText(context, R.string.layouts_reset_done, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}
