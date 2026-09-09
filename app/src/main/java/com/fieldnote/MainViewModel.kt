package com.fieldnote

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import com.fieldnote.data.sync.ConflictPolicy
import com.fieldnote.data.sync.FolderSyncManager
import com.fieldnote.data.sync.SafSessionStore
import com.fieldnote.data.sync.SyncPhase
import com.fieldnote.data.sync.SyncScheduler
import com.fieldnote.data.sync.SyncSettingsStore
import com.fieldnote.data.sync.SyncSnapshot
import com.fieldnote.data.sync.SyncStatusMonitor
import com.fieldnote.data.sync.hasPersistedSyncPermission
import com.fieldnote.update.AppUpdateChecker
import com.fieldnote.update.UpdateCheckResult
import com.fieldnote.update.UpdateInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val message: String = "동기화할 폴더를 선택하세요.",
    val conflictPolicy: ConflictPolicy = ConflictPolicy.Newest
)

/** Whether a newer `update.apk` was found at the sync folder root. See [AppUpdateChecker]. */
data class UpdateUiState(
    val checking: Boolean = false,
    val availableVersionName: String? = null,
    val message: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: FieldNoteRepository = LocalFieldNoteRepository()
    private val localStore = LocalNoteStore.get(application)
    private val session = SafSessionStore(application)
    private val syncSettings = SyncSettingsStore(application)
    private val syncManager = FolderSyncManager(application)

    val uiState = MainUiState()
    val featureStatuses: Flow<List<FeatureStatus>> = repository.featureStatuses

    var syncState by mutableStateOf(loadSyncState())
        private set

    var updateState by mutableStateOf(UpdateUiState())
        private set
    private var pendingUpdate: UpdateInfo? = null
    private var updateCheckJob: Job? = null

    var currentNote by mutableStateOf(localStore.ensureSeedData().toDocument())
        private set

    val todos = mutableStateListOf<TodoItem>()
    val noteTodoPlacements = mutableStateListOf<NoteTodoPlacement>()

    // Debounces the sync trigger while pen strokes keep calling saveNoteContent() in quick
    // succession, so a drawing session enqueues one sync shortly after the user pauses instead
    // of one per stroke.
    private var syncDebounceJob: Job? = null

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
            refreshUpdate()
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
                checkForUpdate(uri)
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
        updateCheckJob?.cancel()
        pendingUpdate = null
        updateState = UpdateUiState()
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
                checkForUpdate(treeUri)
            } catch (error: Exception) {
                syncFailed(error)
            } finally {
                syncState = syncState.copy(syncing = false)
            }
        }
    }

    /**
     * Looks for a newer `update.apk` at the sync folder root. This is independent from note-data
     * sync so adding only an APK does not depend on a successful Notes/Todos synchronization.
     */
    fun refreshUpdate() {
        val treeUriString = session.treeUri
        if (treeUriString == null) {
            pendingUpdate = null
            updateState = UpdateUiState(message = "먼저 동기화 폴더를 선택하세요.")
            return
        }
        val treeUri = Uri.parse(treeUriString)
        if (!getApplication<Application>().hasPersistedSyncPermission(treeUri)) {
            pendingUpdate = null
            updateState = UpdateUiState(message = "폴더 접근 권한이 사라졌습니다. 동기화 폴더를 다시 선택하세요.")
            return
        }
        checkForUpdate(treeUri)
    }

    private fun checkForUpdate(treeUri: Uri) {
        updateCheckJob?.cancel()
        pendingUpdate = null
        updateState = UpdateUiState(checking = true, message = "update.apk를 확인하는 중...")
        updateCheckJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { AppUpdateChecker.check(getApplication(), treeUri) }
            when (result) {
                is UpdateCheckResult.Available -> {
                    pendingUpdate = result.info
                    updateState = UpdateUiState(
                        availableVersionName = result.info.versionName,
                        message = "새 버전 ${result.info.versionName}을(를) 설치할 수 있습니다."
                    )
                }
                is UpdateCheckResult.NotFound -> {
                    pendingUpdate = null
                    updateState = UpdateUiState(message = "동기화 폴더 루트에서 update.apk를 찾지 못했습니다.")
                }
                is UpdateCheckResult.UpToDate -> {
                    pendingUpdate = null
                    updateState = UpdateUiState(message = "최신 버전을 사용 중입니다(update.apk도 ${result.versionName}).")
                }
                is UpdateCheckResult.Failed -> {
                    pendingUpdate = null
                    updateState = UpdateUiState(message = "업데이트 확인 실패: ${result.message}")
                }
            }
        }
    }

    /** Opens the system installer for the update found by [checkForUpdate]. Android still
     * requires the user to confirm the install themselves in that screen. */
    fun installUpdate() {
        val info = pendingUpdate ?: return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            updateState = updateState.copy(
                message = "설치 권한이 필요합니다. 열린 설정에서 '이 출처 허용'을 켠 뒤 업데이트를 다시 누르세요."
            )
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        try {
            app.startActivity(AppUpdateChecker.installIntent(app, info.apkFile))
        } catch (error: Exception) {
            updateState = updateState.copy(message = "설치 화면을 열지 못했습니다: ${error.message ?: "알 수 없는 오류"}")
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
        if (session.connected) {
            syncDebounceJob?.cancel()
            syncDebounceJob = viewModelScope.launch {
                delay(1200)
                SyncScheduler.enqueue(getApplication())
            }
        }
    }

    /** Clears the conflict backlog without touching note/todo content -- for a stuck count left
     * over from a since-fixed sync race, once the user has confirmed the current content is fine. */
    fun clearConflicts() {
        localStore.resolveAllConflicts()
        val snapshot = SyncSnapshot(
            phase = syncState.phase.takeUnless { it == SyncPhase.Conflict } ?: SyncPhase.Idle,
            message = "충돌 기록을 지웠습니다.",
            lastSyncedAt = session.lastSyncedAt,
            pendingChanges = localStore.pendingCount(),
            conflicts = 0
        )
        SyncStatusMonitor.update(snapshot)
        applySyncSnapshot(snapshot)
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
        message = if (session.connected) "동기화 폴더 연결 정보를 복원했습니다." else "동기화할 폴더를 선택하세요.",
        conflictPolicy = syncSettings.conflictPolicy
    )

    /** Sets how a future sync conflict is resolved (see [ConflictPolicy]). Persists across app
     * restarts and across disconnecting/reconnecting a sync folder. */
    fun setConflictPolicy(policy: ConflictPolicy) {
        syncSettings.conflictPolicy = policy
        syncState = syncState.copy(conflictPolicy = policy)
    }

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
