package com.fieldnote.data.sync

import android.content.Context
import android.net.Uri
import com.fieldnote.data.LocalNoteStore
import com.fieldnote.data.NoteRecord
import com.fieldnote.data.TodoRecord
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SyncResult(val uploaded: Int, val downloaded: Int, val conflicts: Int)

/**
 * Syncs notes/todos against a user-picked SAF folder (see [SafFileStore]) instead of the Drive
 * REST API. The local dirty/revision bookkeeping in [LocalNoteStore] and the conflict rules in
 * [SyncDecision] are unchanged from the previous OAuth-based implementation -- only the transport
 * (network calls -> SAF file I/O) was replaced.
 */
class FolderSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val local = LocalNoteStore.get(appContext)
    private val store = SafFileStore(appContext)

    suspend fun sync(rootTreeUri: Uri): SyncResult = mutex.withLock {
        syncLocked(rootTreeUri)
    }

    // A manual "동기화" tap (from the ViewModel) and a WorkManager-triggered run can both call
    // sync() around the same time. Without this, two passes reading/writing the same
    // LocalNoteStore rows and remote files concurrently could race -- one pass's remote write
    // landing before the other's "last synced" bookkeeping update -- which looks exactly like a
    // real two-writer conflict. A process-wide lock (shared by every FolderSyncManager instance,
    // since each caller constructs its own) makes sync passes queue up instead of interleaving.
    private suspend fun syncLocked(rootTreeUri: Uri): SyncResult = withContext(Dispatchers.IO) {
        local.ensureSeedData()
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = SyncPhase.Syncing,
                message = "변경된 항목을 동기화하는 중...",
                pendingChanges = local.pendingCount(),
                conflicts = local.conflictCount()
            )
        )
        val root = store.rootFolder(rootTreeUri)
        val notesFolder = store.childFolder(root, "Notes")
        val todosFolder = store.childFolder(root, "Todos")
        val notesResult = syncNotes(notesFolder)
        val todosResult = syncTodos(todosFolder)
        val result = SyncResult(
            uploaded = notesResult.uploaded + todosResult.uploaded,
            downloaded = notesResult.downloaded + todosResult.downloaded,
            conflicts = local.conflictCount()
        )
        val now = System.currentTimeMillis()
        val phase = if (result.conflicts > 0) SyncPhase.Conflict else SyncPhase.Synced
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = phase,
                message = if (phase == SyncPhase.Conflict) {
                    "충돌이 있는 채로 동기화를 마쳤습니다. 로컬과 폴더 사본을 모두 보존했습니다."
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

    private fun syncNotes(folder: SyncFolderHandle): SyncResult {
        var uploaded = 0
        var downloaded = 0
        val remoteFiles = store.listJsonFiles(folder)
        val remoteIds = mutableSetOf<String>()

        remoteFiles.forEach { remoteFile ->
            val id = remoteFile.name.removeSuffix(".json")
            remoteIds += id
            val remote = runCatching { JSONObject(store.readText(remoteFile)) }.getOrNull() ?: return@forEach
            val remoteRevision = remote.optLong("revision", 0)
            val remoteUpdatedAt = runCatching { parseTime(remote.getString("updatedAt")) }.getOrDefault(0L)
            val localNote = local.findNote(id)

            val action = SyncDecision.decide(
                localExists = localNote != null,
                localPristine = localNote != null && isPristine(localNote),
                localDirty = localNote?.dirty ?: false,
                localRevision = localNote?.revision ?: 0,
                localUpdatedAt = localNote?.updatedAt ?: 0,
                localLastSyncedRevision = localNote?.lastSyncedRevision ?: 0,
                localLastSyncedUpdatedAt = localNote?.lastSyncedUpdatedAt ?: 0,
                remoteRevision = remoteRevision,
                remoteUpdatedAt = remoteUpdatedAt
            )
            when (action) {
                SyncAction.DOWNLOAD -> {
                    local.upsertRemoteNote(remote.toNoteRecord(id, remoteRevision, remoteUpdatedAt))
                    downloaded++
                }
                SyncAction.CONFLICT -> {
                    val note = checkNotNull(localNote)
                    local.recordConflict(
                        "note", note.id, note.revision, remoteRevision,
                        note.updatedAt, remoteUpdatedAt
                    )
                }
                SyncAction.UPLOAD -> {
                    val note = checkNotNull(localNote)
                    store.upsertJson(folder, id + ".json", note.toJson().toString())
                    local.markNoteSynced(note.id, id + ".json", note.revision, note.updatedAt)
                    uploaded++
                }
                SyncAction.NONE -> Unit
            }
        }

        local.dirtyNotes().filterNot { it.id in remoteIds }.forEach { note ->
            store.upsertJson(folder, note.id + ".json", note.toJson().toString())
            local.markNoteSynced(note.id, note.id + ".json", note.revision, note.updatedAt)
            uploaded++
        }
        return SyncResult(uploaded, downloaded, local.conflictCount())
    }

    private fun syncTodos(folder: SyncFolderHandle): SyncResult {
        var uploaded = 0
        var downloaded = 0
        val remoteFiles = store.listJsonFiles(folder)
        val remoteIds = mutableSetOf<Long>()

        remoteFiles.forEach { remoteFile ->
            val id = remoteFile.name.removeSuffix(".json").toLongOrNull() ?: return@forEach
            remoteIds += id
            val remote = runCatching { JSONObject(store.readText(remoteFile)) }.getOrNull() ?: return@forEach
            val remoteRevision = remote.optLong("revision", 0)
            val remoteUpdatedAt = runCatching { parseTime(remote.getString("updatedAt")) }.getOrDefault(0L)
            val localTodo = local.findTodo(id)

            val action = SyncDecision.decide(
                localExists = localTodo != null,
                localPristine = localTodo != null && isPristine(localTodo),
                localDirty = localTodo?.dirty ?: false,
                localRevision = localTodo?.revision ?: 0,
                localUpdatedAt = localTodo?.updatedAt ?: 0,
                localLastSyncedRevision = localTodo?.lastSyncedRevision ?: 0,
                localLastSyncedUpdatedAt = localTodo?.lastSyncedUpdatedAt ?: 0,
                remoteRevision = remoteRevision,
                remoteUpdatedAt = remoteUpdatedAt
            )
            when (action) {
                SyncAction.DOWNLOAD -> {
                    local.upsertRemoteTodo(remote.toTodoRecord(id, remoteRevision, remoteUpdatedAt))
                    downloaded++
                }
                SyncAction.CONFLICT -> {
                    val todo = checkNotNull(localTodo)
                    local.recordConflict(
                        "todo", todo.id.toString(), todo.revision, remoteRevision,
                        todo.updatedAt, remoteUpdatedAt
                    )
                }
                SyncAction.UPLOAD -> {
                    val todo = checkNotNull(localTodo)
                    store.upsertJson(folder, id.toString() + ".json", todo.toJson().toString())
                    local.markTodoSynced(todo.id, id.toString() + ".json", todo.revision, todo.updatedAt)
                    uploaded++
                }
                SyncAction.NONE -> Unit
            }
        }

        local.dirtyTodos().filterNot { it.id in remoteIds }.forEach { todo ->
            store.upsertJson(folder, todo.id.toString() + ".json", todo.toJson().toString())
            local.markTodoSynced(todo.id, todo.id.toString() + ".json", todo.revision, todo.updatedAt)
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

    private fun JSONObject.toNoteRecord(id: String, revision: Long, updatedAt: Long) = NoteRecord(
        id = id,
        title = optString("title", "Untitled"),
        content = optString("content", """{"pages":{"1":[]}}"""),
        updatedAt = updatedAt,
        revision = revision,
        dirty = false,
        remoteFileId = id + ".json",
        lastSyncedRevision = revision,
        lastSyncedUpdatedAt = updatedAt
    )

    private fun JSONObject.toTodoRecord(id: Long, revision: Long, updatedAt: Long) = TodoRecord(
        id = id,
        title = getString("title"),
        dueDay = if (isNull("dueDay")) null else optInt("dueDay"),
        completed = optBoolean("completed"),
        updatedAt = updatedAt,
        revision = revision,
        dirty = false,
        remoteFileId = id.toString() + ".json",
        lastSyncedRevision = revision,
        lastSyncedUpdatedAt = updatedAt
    )

    private fun parseTime(value: String): Long = Instant.parse(value).toEpochMilli()

    companion object {
        private val mutex = Mutex()
    }
}
