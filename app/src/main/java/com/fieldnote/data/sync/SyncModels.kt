package com.fieldnote.data.sync

data class SyncSnapshot(
    val phase: SyncPhase = SyncPhase.Idle,
    val message: String = "동기화할 폴더를 선택하세요.",
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
    // Originally "re-authenticate the Google account"; now repurposed for the SAF flow to mean
    // "the persisted folder permission is gone -- ask the user to pick the folder again".
    AuthenticationRequired,
    Error
}

/** How [FolderSyncManager] should resolve a note/todo that changed on both sides since the last
 * successful sync. The loser is never discarded -- it's still written to `Conflicts/` -- this
 * only decides which copy becomes canonical going forward. */
enum class ConflictPolicy {
    /** Whichever copy has the later `updatedAt` wins. The default. */
    Newest,
    /** This device's copy always wins. */
    Local,
    /** The sync folder's copy always wins. */
    Remote
}
