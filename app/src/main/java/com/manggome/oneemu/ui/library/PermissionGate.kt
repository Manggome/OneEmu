package com.manggome.oneemu.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.manggome.oneemu.R
import com.manggome.oneemu.util.StorageAccess

/**
 * Shows [content] only when the app has "모든 파일 접근" (Android 11+); otherwise a full-screen
 * explanation with a button into the system settings page. Re-checks every time the screen resumes,
 * so returning from Settings continues automatically. Below Android 11 the check is always true.
 */
@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    var granted by remember { mutableStateOf(StorageAccess.hasAllFilesAccess()) }
    LifecycleResumeEffect(Unit) {
        granted = StorageAccess.hasAllFilesAccess()
        onPauseOrDispose { }
    }
    if (granted) {
        content()
    } else {
        PermissionExplanation(onRecheck = { granted = StorageAccess.hasAllFilesAccess() })
    }
}

@Composable
private fun PermissionExplanation(onRecheck: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                Text(stringResource(R.string.lib_perm_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.lib_perm_desc), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.lib_perm_steps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { runCatching { context.startActivity(StorageAccess.allFilesAccessIntent(context)) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.lib_perm_button)) }
                TextButton(onClick = onRecheck) { Text(stringResource(R.string.lib_perm_recheck)) }
            }
        }
    }
}
