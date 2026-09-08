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

class DriveSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_session", Context.MODE_PRIVATE)

    val connected: Boolean get() = prefs.getBoolean("connected", false)
    val accountEmail: String? get() = prefs.getString("account_email", null)
    val lastSyncedAt: Long? get() = prefs.getLong("last_synced_at", 0L).takeIf { it > 0L }

    fun connect(email: String) {
        prefs.edit().putBoolean("connected", true).putString("account_email", email).apply()
    }

    fun markSynced(at: Long) {
        prefs.edit().putLong("last_synced_at", at).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Caches the Drive folder ids used for sync, tagged per-marker with the account they were
 * resolved for. Every read is validated against Drive before use ([DriveRestClient.folderExists])
 * so a folder trashed from the web, or a cache left over from a previous account, self-heals
 * instead of failing forever or silently syncing into the wrong place.
 */
class DriveFolderRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_folders", Context.MODE_PRIVATE)

    fun ensureRoot(client: DriveRestClient, accountEmail: String): String =
        resolve(client, accountEmail, MARKER_ROOT, ROOT_NAME, parentId = null)

    fun ensureNotes(client: DriveRestClient, accountEmail: String): String {
        val root = ensureRoot(client, accountEmail)
        return resolve(client, accountEmail, MARKER_NOTES, "Notes", parentId = root)
    }

    fun ensureTodos(client: DriveRestClient, accountEmail: String): String {
        val root = ensureRoot(client, accountEmail)
        return resolve(client, accountEmail, MARKER_TODOS, "Todos", parentId = root)
    }

    fun ensureAttachments(client: DriveRestClient, accountEmail: String): String {
        val root = ensureRoot(client, accountEmail)
        return resolve(client, accountEmail, MARKER_ATTACHMENTS, "Attachments", parentId = root)
    }

    private fun resolve(
        client: DriveRestClient,
        accountEmail: String,
        marker: String,
        name: String,
        parentId: String?
    ): String {
        val cachedId = prefs.getString(marker, null)
        val cachedAccount = prefs.getString(accountKey(marker), null)
        if (cachedId != null && cachedAccount == accountEmail && client.folderExists(cachedId)) {
            return cachedId
        }
        // ensureFolder looks the folder up by its appProperties marker (not by cached id), so a
        // second device -- or this device after a cache miss -- finds the same Drive folder
        // instead of creating a duplicate tree.
        val resolvedId = client.ensureFolder(name, parentId, marker)
        prefs.edit()
            .putString(marker, resolvedId)
            .putString(accountKey(marker), accountEmail)
            .apply()
        return resolvedId
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Cached ids for the settings-screen diagnostics panel. Not validated against Drive. */
    fun snapshot() = FolderSnapshot(
        rootId = prefs.getString(MARKER_ROOT, null),
        notesId = prefs.getString(MARKER_NOTES, null),
        todosId = prefs.getString(MARKER_TODOS, null),
        attachmentsId = prefs.getString(MARKER_ATTACHMENTS, null)
    )

    private fun accountKey(marker: String) = marker + "_account"

    companion object {
        const val ROOT_NAME = "MyNoteApp"
        private const val MARKER_ROOT = "root"
        private const val MARKER_NOTES = "notes"
        private const val MARKER_TODOS = "todos"
        private const val MARKER_ATTACHMENTS = "attachments"
    }
}

data class FolderSnapshot(
    val rootId: String?,
    val notesId: String?,
    val todosId: String?,
    val attachmentsId: String?
)
