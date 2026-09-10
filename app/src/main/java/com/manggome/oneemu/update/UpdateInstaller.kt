package com.manggome.oneemu.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Hands a downloaded APK to the system package installer. */
object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** True when this app may launch the package installer (Android 8+ "unknown sources" per app). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user allows OneEmu to install apps. */
    fun openUnknownSourcesSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Starts the installer for [file] (must live under the FileProvider "updates" cache path).
     * Returns false when the install permission is missing; call [openUnknownSourcesSettings] then.
     */
    fun install(context: Context, file: File): Boolean {
        if (!canInstall(context)) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
