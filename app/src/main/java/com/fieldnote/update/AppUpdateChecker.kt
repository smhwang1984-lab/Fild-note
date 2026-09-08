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

/** Result of [AppUpdateChecker.check]. Kept explicit (instead of a nullable [UpdateInfo]) so a
 * lookup/read failure is never indistinguishable from "no update.apk here" or "already current" --
 * all three used to collapse to the same silent null, which made a real problem (wrong filename,
 * folder permission hiccup, corrupt copy, ...) look identical to "nothing to do." */
sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object NotFound : UpdateCheckResult()
    data class UpToDate(val versionName: String) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

/**
 * Looks for a fixed-name `update.apk` at the root of the user's sync folder (next to `Notes/`,
 * `Todos/`, `Conflicts/`) and offers it as an install if its version is newer than what's
 * running. Android always makes the user confirm the actual install in the system installer UI
 * for a non-Play-Store APK -- this class only automates getting to that screen, it cannot skip it.
 */
object AppUpdateChecker {
    private const val UPDATE_FILE_NAME = "update.apk"

    fun check(context: Context, rootTreeUri: Uri): UpdateCheckResult {
        val appContext = context.applicationContext
        return try {
            val store = SafFileStore(appContext)
            val root = store.rootFolder(rootTreeUri)
            // Case/whitespace-insensitive: SAF providers (Drive included) preserve whatever name
            // was typed on whichever device uploaded the file, and a strict exact-name lookup
            // silently found nothing for anything but a perfect match.
            val remoteApk = store.findFile(root, UPDATE_FILE_NAME)
                ?: return UpdateCheckResult.NotFound

            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val localApk = File(updatesDir, UPDATE_FILE_NAME)
            store.copyToLocalFile(remoteApk, localApk)

            val packageInfo = appContext.packageManager.getPackageArchiveInfo(localApk.absolutePath, 0)
            if (packageInfo == null) {
                localApk.delete()
                return UpdateCheckResult.Failed("update.apk 파일을 읽을 수 없습니다(손상되었거나 올바른 APK가 아님).")
            }
            val candidateVersionCode = packageInfo.longVersionCodeCompat()
            val candidateVersionName = packageInfo.versionName ?: "?"
            if (candidateVersionCode <= BuildConfig.VERSION_CODE.toLong()) {
                localApk.delete()
                return UpdateCheckResult.UpToDate(candidateVersionName)
            }
            UpdateCheckResult.Available(
                UpdateInfo(versionName = candidateVersionName, versionCode = candidateVersionCode, apkFile = localApk)
            )
        } catch (error: Exception) {
            UpdateCheckResult.Failed(error.message ?: "업데이트 확인 중 오류가 발생했습니다.")
        }
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
