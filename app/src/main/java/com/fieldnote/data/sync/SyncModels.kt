package com.fieldnote.data.sync

data class SyncSnapshot(
    val phase: SyncPhase = SyncPhase.Idle,
    val message: String = "Google 계정을 연결하세요.",
    val lastSyncedAt: Long? = null,
    val pendingChanges: Int = 0,
    val conflicts: Int = 0
)

enum class SyncPhase {
    Idle,
    Offline,
    Syncing,
    Synced,
    Conflict,
    AuthenticationRequired,
    Error
}
