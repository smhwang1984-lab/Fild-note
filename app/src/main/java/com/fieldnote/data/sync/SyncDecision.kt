package com.fieldnote.data.sync

/** What [FolderSyncManager] should do with one note/todo when reconciling local vs. remote. */
enum class SyncAction { DOWNLOAD, UPLOAD, CONFLICT, NONE }

/**
 * Pure decision logic for reconciling one local record against its remote counterpart. Extracted
 * out of the note/todo sync loops (which were near-identical duplicates of this same branch) so
 * the trickiest part of sync -- conflict detection -- can be unit-tested without any I/O.
 */
object SyncDecision {
    fun decide(
        localExists: Boolean,
        localPristine: Boolean,
        localDirty: Boolean,
        localRevision: Long,
        localUpdatedAt: Long,
        localLastSyncedRevision: Long,
        localLastSyncedUpdatedAt: Long,
        remoteRevision: Long,
        remoteUpdatedAt: Long
    ): SyncAction {
        // No local copy yet, or it's still the untouched seed data: take whatever is remote.
        if (!localExists || localPristine) return SyncAction.DOWNLOAD

        val remoteChanged = remoteRevision > localLastSyncedRevision ||
            remoteUpdatedAt > localLastSyncedUpdatedAt

        return when {
            localDirty && remoteChanged &&
                (localRevision != remoteRevision || localUpdatedAt != remoteUpdatedAt) ->
                SyncAction.CONFLICT
            remoteChanged && !localDirty -> SyncAction.DOWNLOAD
            localDirty -> SyncAction.UPLOAD
            else -> SyncAction.NONE
        }
    }
}
