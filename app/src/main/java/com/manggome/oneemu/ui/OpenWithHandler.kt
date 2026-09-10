package com.manggome.oneemu.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.emu.EmulatorActivity
import com.manggome.oneemu.util.StorageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "다른 앱으로 열기" support: a ROM opened from a file manager (ACTION_VIEW) is added to the
 * library and launched straight away.
 */
object OpenWithHandler {
    fun isOpenIntent(intent: Intent?): Boolean = intent?.action == Intent.ACTION_VIEW && intent.data != null

    /** Returns true when the intent was handled (a game was launched or an error toast shown). */
    suspend fun handle(context: Context, intent: Intent): Boolean {
        val uri: Uri = intent.data ?: return false
        val app = OneEmuApp.get()
        val file: File? = when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.isFile }
            "content" -> StorageAccess.treeUriToPath(context, uri)?.takeIf { it.isFile }
            else -> null
        }
        if (file == null) {
            Toast.makeText(context, "이 위치의 파일은 직접 열 수 없습니다. 라이브러리에서 폴더를 추가해 주세요.", Toast.LENGTH_LONG).show()
            return true
        }
        val game = withContext(Dispatchers.IO) { app.scanner.addFile(file) }
        if (game == null) {
            Toast.makeText(context, "지원하지 않는 파일입니다: ${file.name}", Toast.LENGTH_LONG).show()
            return true
        }
        context.startActivity(EmulatorActivity.intent(context, game.id))
        return true
    }
}
