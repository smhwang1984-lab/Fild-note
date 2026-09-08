package com.fieldnote.data.sync

import android.content.Context
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

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val session = DriveSessionStore(applicationContext)
        if (!session.connected) return Result.success()
        return try {
            val token = GoogleDriveAuthorization(applicationContext).silentToken()
            if (token == null) {
                SyncStatusMonitor.update(
                    SyncSnapshot(
                        phase = SyncPhase.AuthenticationRequired,
                        message = "설정 화면에서 Google Drive를 다시 연결하세요.",
                        lastSyncedAt = session.lastSyncedAt,
                        pendingChanges = LocalNoteStore.get(applicationContext).pendingCount()
                    )
                )
                Result.failure()
            } else {
                DriveSyncManager(applicationContext).sync(token, session.accountEmail)
                Result.success()
            }
        } catch (error: IOException) {
            SyncStatusMonitor.update(
                SyncSnapshot(
                    phase = SyncPhase.Offline,
                    message = "오프라인 상태입니다. 변경 사항은 이 기기에 안전하게 저장되어 있습니다.",
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
                    message = error.message ?: "Drive 동기화에 실패했습니다.",
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
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(15, TimeUnit.MINUTES)
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
