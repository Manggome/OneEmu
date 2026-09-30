package com.manggome.oneemu.ui.library

import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.R
import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.library.WipiSaves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Confirms, then replaces a feature-phone game's saves with an exported `.wipisave.zip`. */
@Composable
fun WipiSaveImportDialog(game: GameEntity, uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val okMsg = stringResource(R.string.wipi_save_imported)
    val failMsg = stringResource(R.string.wipi_save_import_failed, "%s")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wipi_save_import)) },
        text = { Text(stringResource(R.string.wipi_save_import_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val input = context.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다")
                            input.use { WipiSaves.import(OneEmuApp.get().dirs, File(game.path), it) }
                        }
                    }
                    val msg = result.fold({ okMsg }, { failMsg.format(it.message ?: it.javaClass.simpleName) })
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onDismiss()
                }
            }) { Text(stringResource(R.string.wipi_save_import)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
