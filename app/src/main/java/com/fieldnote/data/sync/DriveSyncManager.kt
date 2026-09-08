package com.fieldnote.data.sync

import android.content.Context
import com.fieldnote.data.LocalNoteStore
import com.fieldnote.data.NoteRecord
import com.fieldnote.data.TodoRecord
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: Int)

class DriveSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val local = LocalNoteStore.get(appContext)
    private val folders = DriveFolderRegistry(appContext)
    private val session = DriveSessionStore(appContext)
    private val authorization = GoogleDriveAuthorization(appContext)

    suspend fun sync(accessToken: String, expectedEmail: String? = null): SyncResult =
        withContext(Dispatchers.IO) {
            local.ensureSeedData()
            SyncStatusMonitor.update(
                SyncSnapshot(
                    phase = SyncPhase.Syncing,
                    message = "변경된 항목을 동기화하는 중...",
                    lastSyncedAt = session.lastSyncedAt,
                    pendingChanges = local.pendingCount(),
                    conflicts = local.conflictCount()
                )
            )
            // The access token from OAuth expires in about an hour. If a request comes back
            // 401 mid-sync, DriveRestClient calls this once to get a fresh token silently
            // (already on Dispatchers.IO here, so a blocking wait for the Task is fine) instead
            // of failing the whole sync.
            val drive = DriveRestClient(accessToken, refreshToken = {
                runBlocking { authorization.silentToken() }
            })
            val email = drive.currentUserEmail()
            if (expectedEmail != null && !email.equals(expectedEmail, ignoreCase = true)) {
                throw IllegalStateException("승인된 Google 계정이 바뀌었습니다.")
            }
            session.connect(email)
            // ensureNotes/ensureTodos resolve folders by their Drive appProperties marker and
            // are tagged per-account, so switching accounts (or a stale local cache) never
            // reuses another account's folder id -- each marker re-resolves for `email` on its
            // own instead of relying on a blanket cache wipe here.
            val notesFolder = folders.ensureNotes(drive, email)
            val todosFolder = folders.ensureTodos(drive, email)
            val notesResult = syncNotes(drive, notesFolder)
            val todosResult = syncTodos(drive, todosFolder)
            val result = SyncResult(
                uploaded = notesResult.uploaded + todosResult.uploaded,
                downloaded = notesResult.downloaded + todosResult.downloaded,
                conflicts = local.conflictCount()
            )
            val now = System.currentTimeMillis()
            session.markSynced(now)
            val phase = if (result.conflicts > 0) SyncPhase.Conflict else SyncPhase.Synced
            SyncStatusMonitor.update(
                SyncSnapshot(
                    phase = phase,
                    message = if (phase == SyncPhase.Conflict) {
                        "충돌이 있는 채로 동기화를 마쳤습니다. 로컬과 Drive 사본을 모두 보존했습니다."
                    } else {
                        "동기화 완료."
                    },
                    lastSyncedAt = now,
                    pendingChanges = local.pendingCount(),
                    conflicts = result.conflicts
                )
            )
            result
        }

    suspend fun ensureAttachmentsFolder(accessToken: String): String = withContext(Dispatchers.IO) {
        val drive = DriveRestClient(accessToken, refreshToken = {
            runBlocking { authorization.silentToken() }
        })
        folders.ensureAttachments(drive, drive.currentUserEmail())
    }

    private fun syncNotes(drive: DriveRestClient, folderId: String): SyncResult {
        var uploaded = 0
        var downloaded = 0
        val remoteFiles = drive.listJsonFiles(folderId)
        val remoteIds = mutableSetOf<String>()

        remoteFiles.forEach { remote ->
            val entityId = remote.entityId ?: remote.name.removeSuffix(".json")
            remoteIds += entityId
            val localNote = local.findNote(entityId)
            if (localNote == null || isPristine(localNote)) {
                local.upsertRemoteNote(drive.downloadJson(remote.id).toNoteRecord(remote.id))
                downloaded++
                return@forEach
            }
            val localChanged = localNote.dirty
            val remoteChanged = remote.revision > localNote.lastSyncedRevision ||
                remote.updatedAt > localNote.lastSyncedUpdatedAt
            if (localChanged && remoteChanged &&
                (localNote.revision != remote.revision || localNote.updatedAt != remote.updatedAt)
            ) {
                local.recordConflict(
                    "note", localNote.id, localNote.revision, remote.revision,
                    localNote.updatedAt, remote.updatedAt
                )
            } else if (remoteChanged && !localChanged) {
                local.upsertRemoteNote(drive.downloadJson(remote.id).toNoteRecord(remote.id))
                downloaded++
            } else if (localChanged) {
                drive.updateJson(
                    remote.id, localNote.id + ".json", "note", localNote.id,
                    localNote.revision, localNote.updatedAt, localNote.toJson()
                )
                local.markNoteSynced(localNote.id, remote.id, localNote.revision, localNote.updatedAt)
                uploaded++
            }
        }

        local.dirtyNotes().filterNot { it.id in remoteIds }.forEach { note ->
            val remoteId = drive.createJson(
                folderId, note.id + ".json", "note", note.id,
                note.revision, note.updatedAt, note.toJson()
            )
            local.markNoteSynced(note.id, remoteId, note.revision, note.updatedAt)
            uploaded++
        }
        return SyncResult(uploaded, downloaded, local.conflictCount())
    }

    private fun syncTodos(drive: DriveRestClient, folderId: String): SyncResult {
        var uploaded = 0
        var downloaded = 0
        val remoteFiles = drive.listJsonFiles(folderId)
        val remoteIds = mutableSetOf<Long>()

        remoteFiles.forEach { remote ->
            val entityId = (remote.entityId ?: remote.name.removeSuffix(".json")).toLongOrNull()
                ?: return@forEach
            remoteIds += entityId
            val localTodo = local.findTodo(entityId)
            if (localTodo == null || isPristine(localTodo)) {
                local.upsertRemoteTodo(drive.downloadJson(remote.id).toTodoRecord(remote.id))
                downloaded++
                return@forEach
            }
            val localChanged = localTodo.dirty
            val remoteChanged = remote.revision > localTodo.lastSyncedRevision ||
                remote.updatedAt > localTodo.lastSyncedUpdatedAt
            if (localChanged && remoteChanged &&
                (localTodo.revision != remote.revision || localTodo.updatedAt != remote.updatedAt)
            ) {
                local.recordConflict(
                    "todo", localTodo.id.toString(), localTodo.revision, remote.revision,
                    localTodo.updatedAt, remote.updatedAt
                )
            } else if (remoteChanged && !localChanged) {
                local.upsertRemoteTodo(drive.downloadJson(remote.id).toTodoRecord(remote.id))
                downloaded++
            } else if (localChanged) {
                drive.updateJson(
                    remote.id, localTodo.id.toString() + ".json", "todo", localTodo.id.toString(),
                    localTodo.revision, localTodo.updatedAt, localTodo.toJson()
                )
                local.markTodoSynced(localTodo.id, remote.id, localTodo.revision, localTodo.updatedAt)
                uploaded++
            }
        }

        local.dirtyTodos().filterNot { it.id in remoteIds }.forEach { todo ->
            val remoteId = drive.createJson(
                folderId, todo.id.toString() + ".json", "todo", todo.id.toString(),
                todo.revision, todo.updatedAt, todo.toJson()
            )
            local.markTodoSynced(todo.id, remoteId, todo.revision, todo.updatedAt)
            uploaded++
        }
        return SyncResult(uploaded, downloaded, local.conflictCount())
    }

    private fun isPristine(note: NoteRecord) =
        note.lastSyncedRevision == 0L && note.revision == 1L &&
            note.content == """{"pages":{"1":[]}}"""

    private fun isPristine(todo: TodoRecord) =
        todo.id in 1L..3L && todo.lastSyncedRevision == 0L && todo.revision == 1L

    private fun NoteRecord.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("updatedAt", Instant.ofEpochMilli(updatedAt).toString())
        .put("revision", revision)

    private fun TodoRecord.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("dueDay", dueDay)
        .put("completed", completed)
        .put("updatedAt", Instant.ofEpochMilli(updatedAt).toString())
        .put("revision", revision)

    private fun JSONObject.toNoteRecord(remoteId: String): NoteRecord {
        val updated = parseTime(getString("updatedAt"))
        val revision = getLong("revision")
        return NoteRecord(
            id = getString("id"),
            title = optString("title", "Untitled"),
            content = optString("content", """{"pages":{"1":[]}}"""),
            updatedAt = updated,
            revision = revision,
            dirty = false,
            remoteFileId = remoteId,
            lastSyncedRevision = revision,
            lastSyncedUpdatedAt = updated
        )
    }

    private fun JSONObject.toTodoRecord(remoteId: String): TodoRecord {
        val updated = parseTime(getString("updatedAt"))
        val revision = getLong("revision")
        return TodoRecord(
            id = getLong("id"),
            title = getString("title"),
            dueDay = if (isNull("dueDay")) null else optInt("dueDay"),
            completed = optBoolean("completed"),
            updatedAt = updated,
            revision = revision,
            dirty = false,
            remoteFileId = remoteId,
            lastSyncedRevision = revision,
            lastSyncedUpdatedAt = updated
        )
    }

    private fun parseTime(value: String): Long = Instant.parse(value).toEpochMilli()
}
