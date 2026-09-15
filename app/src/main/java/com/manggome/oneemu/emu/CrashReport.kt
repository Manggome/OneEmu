package com.manggome.oneemu.emu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Detects games that took the whole process down (native crash in a core). A marker file is written
 * when a session starts and removed when it closes cleanly; if it is still there on the next app
 * start, we tell the user and offer to copy this app's recent logcat lines so they can report it.
 */
object CrashMarker {
    private fun file(context: Context) = File(context.cacheDir, "last_session.txt")

    fun write(context: Context, gameTitle: String, gamePath: String, coreId: String) {
        runCatching { file(context).writeText("$gameTitle\n$gamePath\n$coreId\n${System.currentTimeMillis()}") }
    }

    fun clear(context: Context) { runCatching { file(context).delete() } }

    data class Marker(val title: String, val path: String, val coreId: String, val at: Long)

    fun read(context: Context): Marker? = runCatching {
        val lines = file(context).takeIf { it.exists() }?.readLines() ?: return null
        if (lines.size < 4) return null
        Marker(lines[0], lines[1], lines[2], lines[3].toLongOrNull() ?: 0L)
    }.getOrNull()

    /** Recent log lines of our own UID (the crashed process shares it), most relevant tags only. */
    suspend fun collectLog(): String = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "400", "OneEmu:*", "libretro:*", "AndroidRuntime:E", "*:S")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.lines().filterNot { it.startsWith("--------- beginning") }.takeLast(300).joinToString("\n")
        }.getOrElse { "logcat 읽기 실패: ${it.message}" }
    }
}

/** Drop-in for MainActivity: shows the crash notice once after an unclean emulator exit. */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var marker by remember { mutableStateOf<CrashMarker.Marker?>(null) }
    LaunchedEffect(Unit) {
        val m = CrashMarker.read(context)
        // Ignore stale markers (e.g. the process was killed by the system days ago).
        if (m != null && System.currentTimeMillis() - m.at < 24 * 60 * 60 * 1000L) marker = m
        else if (m != null) CrashMarker.clear(context)
    }
    val m = marker ?: return
    AlertDialog(
        onDismissRequest = { CrashMarker.clear(context); marker = null },
        title = { Text("이전 게임 실행이 비정상 종료되었습니다") },
        text = {
            Text(
                "'${m.title}' 실행 중 앱이 강제 종료되었습니다 (코어: ${m.coreId}).\n\n" +
                    "이 코어에서 해당 게임이 불안정할 수 있습니다. 게임 상세 → 코어 선택에서 다른 코어를 시도하거나, " +
                    "로그를 복사해 개발자에게 보내 주세요."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val log = CrashMarker.collectLog()
                    val text = "OneEmu 비정상 종료 보고\n게임: ${m.title}\n파일: ${m.path}\n코어: ${m.coreId}\n\n$log"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OneEmu crash log", text))
                    Toast.makeText(context, "로그를 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
                    CrashMarker.clear(context)
                    marker = null
                }
            }) { Text("로그 복사") }
        },
        dismissButton = {
            TextButton(onClick = { CrashMarker.clear(context); marker = null }) { Text("닫기") }
        },
    )
}
