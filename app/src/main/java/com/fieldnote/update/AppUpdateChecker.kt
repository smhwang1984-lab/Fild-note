package com.fieldnote.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.fieldnote.BuildConfig
import com.fieldnote.data.sync.SafFileStore
import java.io.File

data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkFile: File
)

/**
 * Looks for a fixed-name `update.apk` at the root of the user's sync folder (next to `Notes/`,
 * `Todos/`, `Conflicts/`) and offers it as an install if its version is newer than what's
 * running. Android always makes the user confirm the actual install in the system installer UI
 * for a non-Play-Store APK -- this class only automates getting to that screen, it cannot skip it.
 */
object AppUpdateChecker {
    private const val UPDATE_FILE_NAME = "update.apk"

    /** Returns update info if a newer update.apk is present at the sync folder root, else null. */
    fun check(context: Context, rootTreeUri: Uri): UpdateInfo? {
        val appContext = context.applicationContext
        val store = SafFileStore(appContext)
        val root = store.rootFolder(rootTreeUri)
        val remoteApk = store.findFile(root, UPDATE_FILE_NAME) ?: return null

        val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val localApk = File(updatesDir, UPDATE_FILE_NAME)
        store.copyToLocalFile(remoteApk, localApk)

        val packageInfo = appContext.packageManager.getPackageArchiveInfo(localApk.absolutePath, 0)
        if (packageInfo == null) {
            localApk.delete()
            return null
        }
        val candidateVersionCode = packageInfo.longVersionCodeCompat()
        if (candidateVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
            localApk.delete()
            return null
        }
        return UpdateInfo(
            versionName = packageInfo.versionName ?: "?",
            versionCode = candidateVersionCode,
            apkFile = localApk
        )
    }

    /** Intent that opens the system package installer for [apkFile], via this app's FileProvider. */
    fun installIntent(context: Context, apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
}
