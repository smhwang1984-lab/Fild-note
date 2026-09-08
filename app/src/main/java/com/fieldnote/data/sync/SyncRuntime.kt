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

class DriveFolderRegistry(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("drive_folders", Context.MODE_PRIVATE)

    fun ensureRoot(client: DriveRestClient): String =
        prefs.getString("root", null) ?: client.ensureFolder(ROOT_NAME, null).also {
            prefs.edit().putString("root", it).apply()
        }

    fun ensureNotes(client: DriveRestClient): String {
        val root = ensureRoot(client)
        return prefs.getString("notes", null) ?: client.ensureFolder("Notes", root).also {
            prefs.edit().putString("notes", it).apply()
        }
    }

    fun ensureTodos(client: DriveRestClient): String {
        val root = ensureRoot(client)
        return prefs.getString("todos", null) ?: client.ensureFolder("Todos", root).also {
            prefs.edit().putString("todos", it).apply()
        }
    }

    fun ensureAttachments(client: DriveRestClient): String {
        val root = ensureRoot(client)
        return prefs.getString("attachments", null) ?: client.ensureFolder("Attachments", root).also {
            prefs.edit().putString("attachments", it).apply()
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        const val ROOT_NAME = "MyNoteApp"
    }
}
