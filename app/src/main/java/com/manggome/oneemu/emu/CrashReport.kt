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
        clearNativeCrash(context) // a stale record must not be attributed to this session
        clearJavaCrash(context)
        runCatching { file(context).writeText("$gameTitle\n$gamePath\n$coreId\n${System.currentTimeMillis()}") }
    }

    fun clear(context: Context) { runCatching { file(context).delete() } }

    data class Marker(val title: String, val path: String, val coreId: String, val at: Long)

    fun read(context: Context): Marker? = runCatching {
        val lines = file(context).takeIf { it.exists() }?.readLines() ?: return null
        if (lines.size < 4) return null
        Marker(lines[0], lines[1], lines[2], lines[3].toLongOrNull() ?: 0L)
    }.getOrNull()

    /** The native crash record written by liboneemu's signal handler, if the last crash was native. */
    fun readNativeCrash(context: Context): String? = runCatching {
        File(context.cacheDir, "native_crash.txt").takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clearNativeCrash(context: Context) { runCatching { File(context.cacheDir, "native_crash.txt").delete() } }

    /** Uncaught Kotlin/Java exception of the last crash (stack trace); logcat of a dead process is not readable here. */
    fun readJavaCrash(context: Context): String? = runCatching {
        File(context.cacheDir, "java_crash.txt").takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun clearJavaCrash(context: Context) { runCatching { File(context.cacheDir, "java_crash.txt").delete() } }

    fun installJavaCrashRecorder(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(context.cacheDir, "java_crash.txt").writeText(
                    "thread: ${thread.name}\n" + android.util.Log.getStackTraceString(e)
                )
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Written by liboneemu (frontend LOG lines + core log) for the current/last session; truncated on each load. */
    fun sessionLogFile(context: Context) = File(context.cacheDir, "session_log.txt")
    /** Copy of the session log preserved at app start when the previous session did not end cleanly. */
    fun crashLogFile(context: Context) = File(context.cacheDir, "session_log.crash.txt")

    /** Call first thing in Application.onCreate: keeps the crashed session's log before a new session truncates it. */
    fun preserveCrashLog(context: Context) {
        runCatching {
            val log = sessionLogFile(context)
            if (file(context).exists() && log.exists()) log.copyTo(crashLogFile(context), overwrite = true)
        }
    }

    private fun tail(f: File, maxLines: Int = 400): String = runCatching {
        if (!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrDefault("")

    /**
     * Full text for a bug report: app version, device, game/core and our recent log lines (frontend heartbeats,
     * core log). Used by 로그 복사 in the in-game menu and on the About screen.
     */
    suspend fun buildLogReport(context: Context, gameTitle: String?, gamePath: String?, coreId: String?, crash: Boolean = false): String {
        val log = collectLog()
        val native = readNativeCrash(context)?.let { "네이티브 크래시:\n$it\n\n" } ?: ""
        val java = readJavaCrash(context)?.let { "앱 예외 (Java):\n$it\n\n" } ?: ""
        // The session log file does not depend on logcat permissions (Samsung hides other processes' lines).
        val session = tail(if (crash) crashLogFile(context) else sessionLogFile(context))
            .takeIf { it.isNotBlank() }?.let { "세션 로그 (파일):\n$it\n\n" } ?: ""
        val head = buildString {
            append("OneEmu 실행 로그 (v").append(com.manggome.oneemu.BuildConfig.VERSION_NAME).append(")\n")
            append("기기: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
            if (gameTitle != null) append("게임: ").append(gameTitle).append('\n')
            if (gamePath != null) append("파일: ").append(gamePath).append('\n')
            if (coreId != null) append("코어: ").append(coreId).append('\n')
        }
        return "$head\n$native$java${session}logcat:\n$log"
    }

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
                    val text = "OneEmu 비정상 종료 보고\n" + CrashMarker.buildLogReport(context, m.title, m.path, m.coreId, crash = true)
                    android.util.Log.i("OneEmu", "crash report copied (${text.length} chars, sessionLog=${"세션 로그 (파일)" in text})")
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("OneEmu crash log", text))
                    Toast.makeText(context, "로그를 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
                    CrashMarker.clear(context)
                    CrashMarker.clearNativeCrash(context)
                    runCatching { CrashMarker.crashLogFile(context).delete() }
                    CrashMarker.clearJavaCrash(context)
                    marker = null
                }
            }) { Text("로그 복사") }
        },
        dismissButton = {
            TextButton(onClick = { CrashMarker.clear(context); marker = null }) { Text("닫기") }
        },
    )
}
