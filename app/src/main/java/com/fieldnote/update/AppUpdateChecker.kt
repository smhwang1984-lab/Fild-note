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
    val sourceFileName: String,
    val apkFile: File
)

/** Result of [AppUpdateChecker.check]. */
sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object NotFound : UpdateCheckResult()
    data class UpToDate(val versionName: String) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

/**
 * Finds version-named APK files at the sync-folder root and offers the highest newer valid build.
 * `FieldNote-v1.2.7.apk` and the requested typo `fildnote-v1.2.7.apk` are both accepted.
 * A SHA sidecar is optional because Android validates the APK signature.
 */
object AppUpdateChecker {
    private val UPDATE_FILE_PATTERN = Regex(
        pattern = "^(?:fieldnote|fildnote)-v(\\d+)\\.(\\d+)\\.(\\d+)\\.apk$",
        option = RegexOption.IGNORE_CASE
    )

    internal data class VersionedApkName(
        val fileName: String,
        val versionName: String,
        val parts: List<Int>
    )

    internal fun parseVersionedApkName(fileName: String): VersionedApkName? {
        val trimmed = fileName.trim()
        val match = UPDATE_FILE_PATTERN.matchEntire(trimmed) ?: return null
        val parts = match.groupValues.drop(1).map { value -> value.toIntOrNull() ?: return null }
        return VersionedApkName(trimmed, parts.joinToString("."), parts)
    }

    internal fun compareVersions(left: String, right: String): Int {
        val leftParts = left.split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.split('.').map { it.toIntOrNull() ?: 0 }
        return compareVersionParts(leftParts, rightParts)
    }

    private fun compareVersionParts(left: List<Int>, right: List<Int>): Int {
        val count = maxOf(left.size, right.size)
        for (index in 0 until count) {
            val compared = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (compared != 0) return compared
        }
        return 0
    }

    fun check(context: Context, rootTreeUri: Uri): UpdateCheckResult {
        val appContext = context.applicationContext
        return try {
            val store = SafFileStore(appContext)
            val root = store.rootFolder(rootTreeUri)
            val candidates = store.listFiles(root)
                .mapNotNull { file -> parseVersionedApkName(file.name)?.let { parsed -> file to parsed } }
                .sortedWith { left, right -> compareVersionParts(right.second.parts, left.second.parts) }
            if (candidates.isEmpty()) return UpdateCheckResult.NotFound

            val newerCandidates = candidates.filter { (_, parsed) ->
                compareVersions(parsed.versionName, BuildConfig.VERSION_NAME) > 0
            }
            if (newerCandidates.isEmpty()) {
                return UpdateCheckResult.UpToDate(candidates.first().second.versionName)
            }

            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            var lastFailure: String? = null
            for ((remoteApk, parsed) in newerCandidates) {
                val localApk = File(updatesDir, "FieldNote-v${parsed.versionName}.apk")
                store.copyToLocalFile(remoteApk, localApk)
                val packageInfo = appContext.packageManager.getPackageArchiveInfo(localApk.absolutePath, 0)
                if (packageInfo == null) {
                    localApk.delete()
                    lastFailure = "${parsed.fileName}: 손상되었거나 올바른 APK가 아닙니다."
                    continue
                }
                if (packageInfo.packageName != appContext.packageName) {
                    localApk.delete()
                    lastFailure = "${parsed.fileName}: 다른 앱의 APK입니다(${packageInfo.packageName})."
                    continue
                }
                val actualVersionName = packageInfo.versionName.orEmpty()
                val actualVersionCode = packageInfo.longVersionCodeCompat()
                if (actualVersionName != parsed.versionName) {
                    localApk.delete()
                    lastFailure = "${parsed.fileName}: 파일명 버전과 APK 버전($actualVersionName)이 다릅니다."
                    continue
                }
                if (compareVersions(actualVersionName, BuildConfig.VERSION_NAME) <= 0 ||
                    actualVersionCode <= BuildConfig.VERSION_CODE.toLong()
                ) {
                    localApk.delete()
                    lastFailure = "${parsed.fileName}: 현재 앱보다 높은 빌드가 아닙니다."
                    continue
                }
                return UpdateCheckResult.Available(
                    UpdateInfo(
                        versionName = actualVersionName,
                        versionCode = actualVersionCode,
                        sourceFileName = parsed.fileName,
                        apkFile = localApk
                    )
                )
            }
            UpdateCheckResult.Failed(lastFailure ?: "사용할 수 있는 업데이트 APK가 없습니다.")
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
