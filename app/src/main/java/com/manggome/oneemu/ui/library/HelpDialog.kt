package com.manggome.oneemu.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.model.SystemId
import com.manggome.oneemu.ui.common.InfoDialog
import com.manggome.oneemu.ui.common.SystemChip
import java.io.File

/** How to add folders, where BIOS files go, and which extensions each system accepts. */
@Composable
fun HelpDialog(systemDir: File, cores: CoreRegistry, onDismiss: () -> Unit) {
    // Extensions per system, preferring what the bundled cores actually accept.
    val extensions = remember {
        SystemId.ordered.map { sys ->
            val fromCores = cores.coresFor(sys).flatMap { it.extensions }.map { it.lowercase() }
            val exts = (fromCores.ifEmpty { sys.extensions.toList() }).distinct().sorted()
            val hasCore = cores.defaultCoreFor(sys)?.let { cores.isAvailable(it) } == true
            Triple(sys, exts, hasCore)
        }
    }
    InfoDialog(title = stringResource(R.string.lib_help_title), onDismiss = onDismiss, buttonText = stringResource(R.string.close)) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            HelpSection(stringResource(R.string.lib_help_folder_title), stringResource(R.string.lib_help_folder_desc))
            HelpSection(stringResource(R.string.lib_help_bios_title), stringResource(R.string.lib_help_bios_desc))
            PathText(systemDir.absolutePath)
            Spacer(Modifier.height(16.dp))
            HelpSection(stringResource(R.string.lib_help_ext_title), stringResource(R.string.lib_help_ext_desc))
            for ((sys, exts, hasCore) in extensions) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    SystemChip(sys, Modifier.padding(top = 2.dp), small = true)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Row {
                            Text(sys.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            if (!hasCore) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.lib_help_core_missing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            exts.joinToString(", ") { ".$it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
}
