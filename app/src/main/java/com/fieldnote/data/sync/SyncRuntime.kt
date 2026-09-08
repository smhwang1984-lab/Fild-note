package com.fieldnote.data.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SyncStatusMonitor {
    private val mutableStatus = MutableStateFlow(SyncSnapshot())
    val status: StateFlow<SyncSnapshot> = mutableStatus.asStateFlow()
    fun update(snapshot: SyncSnapshot) {
        mutableStatus.value = snapshot
    }
}

/**
 * Persists the SAF sync folder the user picked. Unlike the previous Drive-API version there is no
 * per-folder id cache to keep in sync with an account: [SafFileStore.childFolder] resolves the
 * `Notes`/`Todos` subfolders by name on every sync, which is cheap over SAF, so there's nothing
 * here to go stale.
 */
class SafSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sync_session", Context.MODE_PRIVATE)

    val treeUri: String? get() = prefs.getString("tree_uri", null)
    val folderName: String? get() = prefs.getString("folder_name", null)
    val lastSyncedAt: Long? get() = prefs.getLong("last_synced_at", 0L).takeIf { it > 0L }
    val connected: Boolean get() = treeUri != null

    fun connect(uri: String, displayName: String) {
        prefs.edit().putString("tree_uri", uri).putString("folder_name", displayName).apply()
    }

    fun markSynced(at: Long) {
        prefs.edit().putLong("last_synced_at", at).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
