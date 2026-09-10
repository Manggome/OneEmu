package com.manggome.oneemu.emu.menu

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.manggome.oneemu.R
import com.manggome.oneemu.emu.EmulatorSession
import com.manggome.oneemu.ui.theme.OneEmuColors
import com.manggome.oneemu.util.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

enum class SlotMode { SAVE, LOAD }

private data class SlotInfo(val slot: Int, val exists: Boolean, val modifiedAt: Long, val thumbStamp: Long)

/**
 * Bottom sheet listing the auto-save slot plus slots 1..9 with thumbnails and timestamps.
 * [onDone] receives a toast message after a successful or failed operation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotPickerSheet(
    mode: SlotMode,
    session: EmulatorSession,
    onDone: (message: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var refresh by remember { mutableIntStateOf(0) }
    var confirmSlot by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    val slots by produceState(initialValue = emptyList<SlotInfo>(), refresh) {
        value = withContext(Dispatchers.IO) {
            (0..AppDirs.SLOT_COUNT).map { slot ->
                val f = session.statePath(slot)
                val t = session.stateThumbPath(slot)
                SlotInfo(slot, f.exists(), f.lastModified(), if (t.exists()) t.lastModified() else 0L)
            }
        }
    }

    fun slotName(slot: Int) = if (slot == AppDirs.AUTO_SLOT) context.getString(R.string.slot_auto) else context.getString(R.string.slot_n, slot)

    fun perform(slot: Int) {
        if (busy) return
        busy = true
        scope.launch {
            val msg = if (mode == SlotMode.SAVE) {
                if (session.saveState(slot)) {
                    if (slot == AppDirs.AUTO_SLOT) context.getString(R.string.slot_saved_auto) else context.getString(R.string.slot_saved, slot)
                } else context.getString(R.string.slot_save_failed)
            } else {
                if (session.loadState(slot)) {
                    if (slot == AppDirs.AUTO_SLOT) context.getString(R.string.slot_loaded_auto) else context.getString(R.string.slot_loaded, slot)
                } else context.getString(R.string.slot_load_failed)
            }
            busy = false
            refresh++
            onDone(msg)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = OneEmuColors.Surface) {
        Text(
            stringResource(if (mode == SlotMode.SAVE) R.string.slot_pick_save else R.string.slot_pick_load),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            items(slots, key = { it.slot }) { info ->
                val enabled = mode == SlotMode.SAVE || info.exists
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled && !busy) {
                            if (mode == SlotMode.LOAD) confirmSlot = info.slot
                            else if (info.exists && info.slot != AppDirs.AUTO_SLOT) confirmSlot = info.slot
                            else perform(info.slot)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SlotThumbnail(session, info)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            slotName(info.slot),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) OneEmuColors.OnSurface else OneEmuColors.OnSurfaceMuted,
                        )
                        Text(
                            if (info.exists) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(info.modifiedAt))
                            else stringResource(R.string.slot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = OneEmuColors.OnSurfaceMuted,
                        )
                    }
                }
            }
        }
    }

    confirmSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { confirmSlot = null },
            title = { Text(slotName(slot)) },
            text = { Text(stringResource(if (mode == SlotMode.LOAD) R.string.slot_load_confirm else R.string.slot_overwrite_confirm)) },
            confirmButton = { TextButton(onClick = { confirmSlot = null; perform(slot) }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { confirmSlot = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SlotThumbnail(session: EmulatorSession, info: SlotInfo) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, info.thumbStamp, info.slot) {
        value = if (info.thumbStamp == 0L) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(session.stateThumbPath(info.slot).absolutePath) }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(width = 96.dp, height = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(OneEmuColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.height(64.dp))
        } else {
            Text("—", color = OneEmuColors.OnSurfaceMuted)
        }
    }
}
