package com.fieldnote.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncDecisionTest {

    @Test
    fun `no local copy downloads the remote version`() {
        val action = SyncDecision.decide(
            localExists = false, localPristine = false, localDirty = false,
            localRevision = 0, localUpdatedAt = 0,
            localLastSyncedRevision = 0, localLastSyncedUpdatedAt = 0,
            remoteRevision = 3, remoteUpdatedAt = 3000
        )
        assertEquals(SyncAction.DOWNLOAD, action)
    }

    @Test
    fun `untouched seed data is replaced by whatever is remote`() {
        val action = SyncDecision.decide(
            localExists = true, localPristine = true, localDirty = true,
            localRevision = 1, localUpdatedAt = 100,
            localLastSyncedRevision = 0, localLastSyncedUpdatedAt = 0,
            remoteRevision = 5, remoteUpdatedAt = 5000
        )
        assertEquals(SyncAction.DOWNLOAD, action)
    }

    @Test
    fun `only local changed uploads`() {
        val action = SyncDecision.decide(
            localExists = true, localPristine = false, localDirty = true,
            localRevision = 2, localUpdatedAt = 200,
            localLastSyncedRevision = 1, localLastSyncedUpdatedAt = 100,
            remoteRevision = 1, remoteUpdatedAt = 100
        )
        assertEquals(SyncAction.UPLOAD, action)
    }

    @Test
    fun `only remote changed downloads`() {
        val action = SyncDecision.decide(
            localExists = true, localPristine = false, localDirty = false,
            localRevision = 1, localUpdatedAt = 100,
            localLastSyncedRevision = 1, localLastSyncedUpdatedAt = 100,
            remoteRevision = 2, remoteUpdatedAt = 200
        )
        assertEquals(SyncAction.DOWNLOAD, action)
    }

    @Test
    fun `both changed since last sync to different states is a conflict`() {
        val action = SyncDecision.decide(
            localExists = true, localPristine = false, localDirty = true,
            localRevision = 2, localUpdatedAt = 200,
            localLastSyncedRevision = 1, localLastSyncedUpdatedAt = 100,
            remoteRevision = 2, remoteUpdatedAt = 250
        )
        assertEquals(SyncAction.CONFLICT, action)
    }

    @Test
    fun `both changed but converged to the same revision and time is not a conflict`() {
        // Same device re-uploading after a sync that already matched: not a real conflict.
        val action = SyncDecision.decide(
            localExists = true, localPristine = false, localDirty = true,
            localRevision = 2, localUpdatedAt = 200,
            localLastSyncedRevision = 1, localLastSyncedUpdatedAt = 100,
            remoteRevision = 2, remoteUpdatedAt = 200
        )
        assertEquals(SyncAction.UPLOAD, action)
    }

    @Test
    fun `nothing changed on either side does nothing`() {
        val action = SyncDecision.decide(
            localExists = true, localPristine = false, localDirty = false,
            localRevision = 1, localUpdatedAt = 100,
            localLastSyncedRevision = 1, localLastSyncedUpdatedAt = 100,
            remoteRevision = 1, remoteUpdatedAt = 100
        )
        assertEquals(SyncAction.NONE, action)
    }
}
