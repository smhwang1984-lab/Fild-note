package com.fieldnote.data.sync

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fieldnote.data.LocalNoteStore
import java.io.IOException
import java.util.concurrent.TimeUnit

/** True if this app still holds a persisted read+write grant for [uri]. */
fun Context.hasPersistedSyncPermission(uri: Uri): Boolean =
    contentResolver.persistedUriPermissions.any {
        it.uri == uri && it.isReadPermission && it.isWritePermission
    }

class SafSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = SafSessionStore(applicationContext)
        val treeUriString = session.treeUri ?: return Result.success()
        val treeUri = Uri.parse(treeUriString)
        return try {
            if (!applicationContext.hasPersistedSyncPermission(treeUri)) {
                SyncStatusMonitor.update(
                    SyncSnapshot(
                        phase = SyncPhase.AuthenticationRequired,
                        message = "동기화 폴더 접근 권한이 사라졌습니다. 설정에서 폴더를 다시 선택하세요.",
                        lastSyncedAt = session.lastSyncedAt,
                        pendingChanges = LocalNoteStore.get(applicationContext).pendingCount()
                    )
                )
                Result.failure()
            } else {
                FolderSyncManager(applicationContext).sync(treeUri)
                session.markSynced(System.currentTimeMillis())
                Result.success()
            }
        } catch (error: IOException) {
            SyncStatusMonitor.update(
                SyncSnapshot(
                    phase = SyncPhase.Offline,
                    message = "폴더에 접근할 수 없습니다(오프라인일 수 있음). 변경 사항은 이 기기에 안전하게 저장되어 있습니다.",
                    lastSyncedAt = session.lastSyncedAt,
                    pendingChanges = LocalNoteStore.get(applicationContext).pendingCount(),
                    conflicts = LocalNoteStore.get(applicationContext).conflictCount()
                )
            )
            Result.retry()
        } catch (error: Exception) {
            SyncStatusMonitor.update(
                SyncSnapshot(
                    phase = SyncPhase.Error,
                    message = error.message ?: "동기화에 실패했습니다.",
                    lastSyncedAt = session.lastSyncedAt,
                    pendingChanges = LocalNoteStore.get(applicationContext).pendingCount(),
                    conflicts = LocalNoteStore.get(applicationContext).conflictCount()
                )
            )
            Result.failure()
        }
    }
}

object SyncScheduler {
    private const val IMMEDIATE = "fieldnote-drive-sync"
    private const val PERIODIC = "fieldnote-drive-periodic-sync"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<SafSyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SafSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
