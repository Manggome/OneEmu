package com.manggome.oneemu.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * OneEmu is sideloaded, so it asks for "모든 파일 접근" (MANAGE_EXTERNAL_STORAGE) and then works with
 * plain filesystem paths, which libretro cores require. SAF is only used as a folder *picker*.
 */
object StorageAccess {
    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            .takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    fun openFolderPickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun openFilePickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }

    /**
     * Converts a SAF tree/document URI from the system picker into a real filesystem path.
     * Works for primary storage (/storage/emulated/0/...) and removable volumes (/storage/XXXX-XXXX/...).
     * Returns null for cloud providers or anything that is not a local file.
     */
    fun treeUriToPath(context: Context, uri: Uri): File? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val docId = runCatching {
            if (DocumentsContract.isTreeUri(uri) && !DocumentsContract.isDocumentUri(context, uri))
                DocumentsContract.getTreeDocumentId(uri)
            else DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return null
        val volume = docId.substringBefore(':')
        val rel = docId.substringAfter(':', "")
        val base: File = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> volumePath(context, volume) ?: File("/storage/$volume")
        }
        val f = if (rel.isEmpty()) base else File(base, rel)
        return f.takeIf { it.exists() }
    }

    private fun volumePath(context: Context, uuid: String): File? {
        val sm = context.getSystemService(StorageManager::class.java) ?: return null
        return sm.storageVolumes.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }?.let { vol ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory else File("/storage/$uuid")
        }
    }
}
