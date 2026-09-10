package com.manggome.oneemu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manggome.oneemu.R
import com.manggome.oneemu.ui.theme.OneEmuColors

@Composable
internal fun CoreOptionsScreen(coreId: String, onBack: () -> Unit) {
    val vm: CoreOptionsViewModel = viewModel(key = "coreopts:$coreId") { CoreOptionsViewModel(coreId) }
    val state by vm.state.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val ready = state as? CoreOptionsViewModel.State.Ready
    val title = ready?.let { stringResource(R.string.core_options_title, it.core.displayName) } ?: stringResource(R.string.cores_core_options)

    SettingsScaffold(
        title = title,
        onBack = onBack,
        actions = {
            if (ready != null && ready.overrides.isNotEmpty()) {
                TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.core_options_reset_all)) }
            }
        },
        scrollable = state is CoreOptionsViewModel.State.Ready,
    ) {
        when (val s = state) {
            CoreOptionsViewModel.State.Loading -> CenterMessage(stringResource(R.string.core_options_loading), spinner = true)
            CoreOptionsViewModel.State.Running -> CenterMessage(stringResource(R.string.core_options_running), stringResource(R.string.core_options_running_desc), onRetry = vm::load)
            is CoreOptionsViewModel.State.Error -> CenterMessage(stringResource(R.string.core_options_load_failed), s.message, onRetry = vm::load)
            is CoreOptionsViewModel.State.Ready -> {
                val hasHidden = s.options.any { !it.visible }
                NoteText(stringResource(R.string.core_options_apply_note))
                if (hasHidden) {
                    SwitchRow(title = stringResource(R.string.core_options_show_advanced), checked = showAdvanced, onCheckedChange = { showAdvanced = it })
                    SettingsDivider()
                }
                val shown = s.options.filter { it.visible || showAdvanced }
                if (shown.isEmpty()) {
                    NoteText(stringResource(R.string.core_options_empty))
                } else {
                    val other = stringResource(R.string.core_options_category_other)
                    shown.groupBy { it.category.ifBlank { other } }.forEach { (category, opts) ->
                        SectionHeader(category)
                        opts.forEach { opt ->
                            OptionRow(
                                option = opt,
                                overridden = opt.key in s.overrides,
                                onSelect = { vm.setValue(opt.key, it) },
                                onReset = { vm.resetOption(opt.key) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.core_options_reset_all),
            body = stringResource(R.string.core_options_reset_all_confirm),
            confirmLabel = stringResource(R.string.reset),
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; vm.resetAll() },
        )
    }
}

@Composable
private fun OptionRow(option: CoreOption, overridden: Boolean, onSelect: (String) -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(option.desc.ifBlank { option.key }, style = MaterialTheme.typography.bodyLarge)
                if (option.info.isNotBlank()) {
                    Text(option.info, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        option.labelFor(option.current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                    if (overridden) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.core_options_modified),
                            style = MaterialTheme.typography.labelLarge,
                            color = OneEmuColors.OnSurfaceMuted,
                        )
                    }
                }
            }
            if (overridden) {
                IconButton(onClick = onReset) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = stringResource(R.string.core_options_reset_one), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            option.values.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            color = if (value == option.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { open = false; if (value != option.current) onSelect(value) },
                )
            }
            if (overridden) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.core_options_reset_one), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { open = false; onReset() },
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(title: String, detail: String? = null, spinner: Boolean = false, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!detail.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onRetry != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}
