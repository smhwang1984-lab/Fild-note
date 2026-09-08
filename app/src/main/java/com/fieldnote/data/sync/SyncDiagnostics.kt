package com.fieldnote.data.sync

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

/**
 * A snapshot of everything needed to compare this install against the Android OAuth client
 * registered in Google Cloud Console: the exact package name and signing SHA-1 the running APK
 * was built with, plus the connection/folder state currently cached on this device. Surfaced in
 * Settings so a mismatch (the most common cause of OAuth error 10 / DEVELOPER_ERROR) can be
 * confirmed by eye instead of guessed at.
 */
data class SyncDiagnostics(
    val packageName: String,
    val signingSha1: String?,
    val accountEmail: String?,
    val folders: FolderSnapshot,
    val lastMessage: String?,
    val lastPhase: SyncPhase
)

object SyncDiagnosticsProvider {
    fun collect(context: Context): SyncDiagnostics {
        val appContext = context.applicationContext
        val session = DriveSessionStore(appContext)
        val snapshot = SyncStatusMonitor.status.value
        return SyncDiagnostics(
            packageName = appContext.packageName,
            signingSha1 = signingSha1(appContext),
            accountEmail = session.accountEmail,
            folders = DriveFolderRegistry(appContext).snapshot(),
            lastMessage = snapshot.message,
            lastPhase = snapshot.phase
        )
    }

    private fun signingSha1(context: Context): String? = try {
        val signature = firstSignature(context)
        signature?.let { formatSha1(it) }
    } catch (error: PackageManager.NameNotFoundException) {
        null
    }

    private fun firstSignature(context: Context): Signature? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
            val signingInfo = info.signingInfo ?: return null
            val certificates = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            certificates?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
    }

    private fun formatSha1(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
        return digest.joinToString(":") { byte -> "%02X".format(byte) }
    }
}
