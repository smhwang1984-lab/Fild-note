package com.fieldnote

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldnote.core.BuildProfile
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.FieldNoteRepository
import com.fieldnote.data.LocalFieldNoteRepository
import com.fieldnote.data.LocalNoteStore
import com.fieldnote.data.NoteRecord
import com.fieldnote.data.sync.FolderSyncManager
import com.fieldnote.data.sync.SafSessionStore
import com.fieldnote.data.sync.SyncPhase
import com.fieldnote.data.sync.SyncScheduler
import com.fieldnote.data.sync.SyncSnapshot
import com.fieldnote.data.sync.SyncStatusMonitor
import com.fieldnote.data.sync.hasPersistedSyncPermission
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private const val DEFAULT_DUE_DAY = 2

data class TodoItem(
    val id: Long,
    val title: String,
    val dueDay: Int? = null,
    val completed: Boolean = false
)

data class NoteTodoPlacement(
    val todoId: Long,
    val x: Float,
    val y: Float,
    val widthDp: Float = 220f,
    val heightDp: Float = 118f,
    val fixed: Boolean = false
)

data class NoteDocument(
    val id: String,
    val title: String,
    val content: String,
    val revision: Long,
    val updatedAt: Long
)

data class MainUiState(
    val versionName: String = BuildProfile.appVersion,
    val targetDevices: List<String> = BuildProfile.targetDevices
)

data class SyncUiState(
    val syncing: Boolean = false,
    val folderName: String? = null,
    val lastSyncedAt: String? = null,
    val phase: SyncPhase = SyncPhase.Idle,
    val pendingChanges: Int = 0,
    val conflicts: Int = 0,
    val message: String = "동기화할 폴더를 선택하세요."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: FieldNoteRepository = LocalFieldNoteRepository()
    private val localStore = LocalNoteStore.get(application)
    private val session = SafSessionStore(application)
    private val syncManager = FolderSyncManager(application)

    val uiState = MainUiState()
    val featureStatuses: Flow<List<FeatureStatus>> = repository.featureStatuses

    var syncState by mutableStateOf(loadSyncState())
        private set

    var currentNote by mutableStateOf(localStore.ensureSeedData().toDocument())
        private set

    val todos = mutableStateListOf<TodoItem>()
    val noteTodoPlacements = mutableStateListOf<NoteTodoPlacement>()

    init {
        reloadFromDatabase()
        viewModelScope.launch {
            SyncStatusMonitor.status.collect { snapshot ->
                applySyncSnapshot(snapshot)
                if (snapshot.phase == SyncPhase.Synced || snapshot.phase == SyncPhase.Conflict) {
                    reloadFromDatabase()
                }
            }
        }
        if (session.connected) {
            SyncScheduler.ensurePeriodic(application)
            SyncScheduler.enqueue(application)
        }
    }

    /**
     * Called with the Uri returned by `ActivityResultContracts.OpenDocumentTree()`. Takes a
     * persistable grant (survives reboots/app restarts) and runs an initial sync. No network
     * request or app registration is involved -- the folder picker itself already authenticated
     * against whichever app backs the chosen folder (e.g. the user's Google Drive app).
     */
    fun onSyncFolderPicked(uri: Uri) {
        if (syncState.syncing) return
        val app = getApplication<Application>()
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (error: SecurityException) {
            syncState = syncState.copy(
                phase = SyncPhase.Error,
                message = "이 폴더에 대한 접근 권한을 받지 못했습니다. 다시 선택해 주세요."
            )
            return
        }
        val displayName = DocumentFile.fromTreeUri(app, uri)?.name ?: uri.lastPathSegment ?: "선택한 폴더"
        session.connect(uri.toString(), displayName)
        syncState = syncState.copy(
            syncing = true,
            folderName = displayName,
            message = "폴더를 확인하고 동기화하는 중..."
        )
        viewModelScope.launch {
            try {
                syncManager.sync(uri)
                session.markSynced(System.currentTimeMillis())
                SyncScheduler.ensurePeriodic(app)
                reloadFromDatabase()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                syncFailed(error)
            } finally {
                syncState = syncState.copy(syncing = false)
            }
        }
    }

    /** Releases the persisted permission (best-effort) and forgets the folder. Local data stays. */
    fun disconnectSyncFolder() {
        if (syncState.syncing) return
        val app = getApplication<Application>()
        session.treeUri?.let { stored ->
            try {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(stored),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Already gone (folder deleted, provider uninstalled) -- nothing to release.
            }
        }
        session.clear()
        SyncScheduler.cancel(app)
        syncState = SyncUiState(message = "동기화 폴더 연결이 해제됐습니다. 기기의 데이터는 그대로입니다.")
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = SyncPhase.AuthenticationRequired,
                message = "동기화 폴더 연결이 해제됐습니다. 기기의 데이터는 그대로입니다.",
                pendingChanges = localStore.pendingCount(),
                conflicts = localStore.conflictCount()
            )
        )
    }

    fun syncNow() {
        if (syncState.syncing) return
        val treeUriString = session.treeUri
        if (treeUriString == null) {
            syncState = syncState.copy(
                phase = SyncPhase.AuthenticationRequired,
                message = "먼저 동기화 폴더를 선택하세요."
            )
            return
        }
        val app = getApplication<Application>()
        val treeUri = Uri.parse(treeUriString)
        if (!app.hasPersistedSyncPermission(treeUri)) {
            syncState = syncState.copy(
                phase = SyncPhase.AuthenticationRequired,
                message = "폴더 접근 권한이 사라졌습니다. 폴더를 다시 선택하세요."
            )
            return
        }
        syncState = syncState.copy(syncing = true)
        viewModelScope.launch {
            try {
                syncManager.sync(treeUri)
                session.markSynced(System.currentTimeMillis())
                reloadFromDatabase()
            } catch (error: Exception) {
                syncFailed(error)
            } finally {
                syncState = syncState.copy(syncing = false)
            }
        }
    }

    fun saveNoteContent(content: String) {
        currentNote = localStore.saveNote(currentNote.id, currentNote.title, content).toDocument()
        localChanged()
    }

    fun addTodo(title: String, dueDay: Int? = null) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val item = localStore.saveTodo(newTodoId(), trimmedTitle, dueDay, false)
        reloadTodos()
        addTodoToNote(item.id)
        localChanged()
    }

    fun addTodoToNote(todoId: Long) {
        if (noteTodoPlacements.any { it.todoId == todoId }) return
        noteTodoPlacements.add(
            NoteTodoPlacement(
                todoId = todoId,
                x = 120f,
                y = 220f + (noteTodoPlacements.size * 36f)
            )
        )
    }

    fun removeNoteTodo(todoId: Long) {
        val placement = noteTodoPlacements.firstOrNull { it.todoId == todoId } ?: return
        if (placement.fixed) return
        noteTodoPlacements.removeAll { it.todoId == todoId }
    }

    fun toggleTodo(todoId: Long) {
        updateTodo(todoId) { it.copy(completed = !it.completed) }
    }

    fun updateTodoDueDay(todoId: Long, dueDay: Int?) {
        updateTodo(todoId) { it.copy(dueDay = dueDay?.coerceIn(1, 30)) }
    }

    fun moveNoteTodo(todoId: Long, dx: Float, dy: Float) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index < 0 || noteTodoPlacements[index].fixed) return
        val current = noteTodoPlacements[index]
        noteTodoPlacements[index] = current.copy(
            x = (current.x + dx).coerceAtLeast(0f),
            y = (current.y + dy).coerceAtLeast(0f)
        )
    }

    fun resizeNoteTodo(todoId: Long, widthDeltaDp: Float, heightDeltaDp: Float) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index < 0 || noteTodoPlacements[index].fixed) return
        val current = noteTodoPlacements[index]
        noteTodoPlacements[index] = current.copy(
            widthDp = (current.widthDp + widthDeltaDp).coerceIn(160f, 420f),
            heightDp = (current.heightDp + heightDeltaDp).coerceIn(92f, 260f)
        )
    }

    fun toggleNoteTodoFixed(todoId: Long) {
        val index = noteTodoPlacements.indexOfFirst { it.todoId == todoId }
        if (index >= 0) {
            noteTodoPlacements[index] = noteTodoPlacements[index].copy(
                fixed = !noteTodoPlacements[index].fixed
            )
        }
    }

    private fun syncFailed(error: Throwable) {
        val snapshot = SyncSnapshot(
            phase = SyncPhase.Error,
            message = error.message ?: "동기화에 실패했습니다.",
            lastSyncedAt = session.lastSyncedAt,
            pendingChanges = localStore.pendingCount(),
            conflicts = localStore.conflictCount()
        )
        SyncStatusMonitor.update(snapshot)
        applySyncSnapshot(snapshot)
    }

    private fun updateTodo(todoId: Long, transform: (TodoItem) -> TodoItem) {
        val current = todos.firstOrNull { it.id == todoId } ?: return
        val updated = transform(current)
        localStore.saveTodo(updated.id, updated.title, updated.dueDay, updated.completed)
        reloadTodos()
        localChanged()
    }

    private fun localChanged() {
        val pending = localStore.pendingCount()
        syncState = syncState.copy(
            pendingChanges = pending,
            message = if (session.connected) "변경 사항이 동기화 폴더 업로드 대기 중입니다." else "이 기기에 오프라인으로 저장됐습니다."
        )
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = if (session.connected) SyncPhase.Idle else SyncPhase.Offline,
                message = syncState.message,
                lastSyncedAt = session.lastSyncedAt,
                pendingChanges = pending,
                conflicts = localStore.conflictCount()
            )
        )
        if (session.connected) SyncScheduler.enqueue(getApplication())
    }

    private fun reloadFromDatabase() {
        currentNote = localStore.listNotes().firstOrNull()?.toDocument() ?: localStore.ensureSeedData().toDocument()
        reloadTodos()
    }

    private fun reloadTodos() {
        val records = localStore.listTodos()
        todos.clear()
        todos.addAll(records.map { TodoItem(it.id, it.title, it.dueDay, it.completed) })
        if (noteTodoPlacements.isEmpty()) {
            records.take(2).forEachIndexed { index, item ->
                noteTodoPlacements += NoteTodoPlacement(
                    todoId = item.id,
                    x = if (index == 0) 80f else 360f,
                    y = if (index == 0) 84f else 156f,
                    fixed = index == 1
                )
            }
        }
    }

    private fun newTodoId(): Long {
        var candidate: Long
        do {
            candidate = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE
        } while (candidate == 0L || localStore.findTodo(candidate) != null)
        return candidate
    }

    private fun loadSyncState(): SyncUiState = SyncUiState(
        folderName = session.folderName,
        lastSyncedAt = session.lastSyncedAt?.let(::formatTime),
        pendingChanges = localStore.pendingCount(),
        conflicts = localStore.conflictCount(),
        message = if (session.connected) "동기화 폴더 연결 정보를 복원했습니다." else "동기화할 폴더를 선택하세요."
    )

    private fun applySyncSnapshot(snapshot: SyncSnapshot) {
        syncState = syncState.copy(
            folderName = session.folderName,
            lastSyncedAt = snapshot.lastSyncedAt?.let(::formatTime),
            phase = snapshot.phase,
            pendingChanges = snapshot.pendingChanges,
            conflicts = snapshot.conflicts,
            message = snapshot.message
        )
    }

    private fun NoteRecord.toDocument() = NoteDocument(id, title, content, revision, updatedAt)

    private fun formatTime(epochMillis: Long): String = FORMATTER.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    )

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
