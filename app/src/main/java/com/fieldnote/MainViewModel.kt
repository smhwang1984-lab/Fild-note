package com.fieldnote

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fieldnote.core.BuildProfile
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.FieldNoteRepository
import com.fieldnote.data.LocalFieldNoteRepository
import com.fieldnote.data.LocalNoteStore
import com.fieldnote.data.NoteRecord
import com.fieldnote.data.sync.DriveFolderRegistry
import com.fieldnote.data.sync.DriveSessionStore
import com.fieldnote.data.sync.DriveSyncManager
import com.fieldnote.data.sync.GoogleDriveAuthorization
import com.fieldnote.data.sync.SyncPhase
import com.fieldnote.data.sync.SyncScheduler
import com.fieldnote.data.sync.SyncSnapshot
import com.fieldnote.data.sync.SyncStatusMonitor
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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

data class GoogleSyncState(
    val authorizing: Boolean = false,
    val accountName: String? = null,
    val syncEnabled: Boolean = false,
    val rootFolderName: String = DriveFolderRegistry.ROOT_NAME,
    val lastSyncedAt: String? = null,
    val phase: SyncPhase = SyncPhase.Idle,
    val pendingChanges: Int = 0,
    val conflicts: Int = 0,
    val message: String = "Connect a Google account."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: FieldNoteRepository = LocalFieldNoteRepository()
    private val localStore = LocalNoteStore.get(application)
    private val session = DriveSessionStore(application)
    private val authorization = GoogleDriveAuthorization(application)
    private val syncManager = DriveSyncManager(application)

    val uiState = MainUiState()
    val featureStatuses: Flow<List<FeatureStatus>> = repository.featureStatuses

    var googleSyncState by mutableStateOf(loadGoogleSyncState())
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

    fun beginGoogleAuthorization(
        activity: Activity,
        launchResolution: (IntentSenderRequest) -> Unit
    ) {
        if (googleSyncState.authorizing) return
        googleSyncState = googleSyncState.copy(
            authorizing = true,
            phase = SyncPhase.Syncing,
            message = "Requesting Google Drive access..."
        )
        viewModelScope.launch {
            try {
                val existingEmail = session.accountEmail
                if (existingEmail != null) {
                    authorization.revoke(activity, existingEmail)
                    clearDriveConnection()
                    googleSyncState = googleSyncState.copy(
                        authorizing = true,
                        phase = SyncPhase.Syncing,
                        message = "Requesting Google Drive access..."
                    )
                }
                requestAuthorization(activity, launchResolution)
            } catch (error: TimeoutCancellationException) {
                authorizationFailed(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                authorizationFailed(error)
            }
        }
    }

    fun completeGoogleAuthorization(intent: Intent?) {
        try {
            finishAuthorization(authorization.tokenFromResult(intent))
        } catch (error: Exception) {
            authorizationFailed(error)
        }
    }

    fun disconnectGoogleAccount(activity: Activity) {
        if (googleSyncState.authorizing) return
        val email = session.accountEmail
        if (email == null) {
            clearDriveConnection()
            return
        }
        googleSyncState = googleSyncState.copy(authorizing = true)
        viewModelScope.launch {
            try {
                authorization.revoke(activity, email)
                clearDriveConnection()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                clearDriveConnection()
                googleSyncState = googleSyncState.copy(
                    phase = SyncPhase.Error,
                    message = "Local connection cleared, but Google access could not be revoked."
                )
            }
        }
    }

    private fun clearDriveConnection() {
        session.clear()
        DriveFolderRegistry(getApplication()).clear()
        SyncScheduler.cancel(getApplication())
        googleSyncState = GoogleSyncState(message = "Google Drive disconnected. Local data is unchanged.")
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = SyncPhase.AuthenticationRequired,
                message = "Google Drive disconnected. Local data is unchanged.",
                pendingChanges = localStore.pendingCount(),
                conflicts = localStore.conflictCount()
            )
        )
    }

    private suspend fun requestAuthorization(
        activity: Activity,
        launchResolution: (IntentSenderRequest) -> Unit
    ) {
        val result = authorization.authorize(activity)
        if (result.hasResolution()) {
            val pendingIntent = checkNotNull(result.pendingIntent) { "Missing OAuth resolution." }
            googleSyncState = googleSyncState.copy(message = "Waiting for Google account permission...")
            launchResolution(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            finishAuthorization(checkNotNull(result.accessToken) { "Missing OAuth token." })
        }
    }

    fun syncNow() {
        if (googleSyncState.authorizing) return
        if (!session.connected) {
            googleSyncState = googleSyncState.copy(
                phase = SyncPhase.AuthenticationRequired,
                message = "Connect a Google account first."
            )
            return
        }
        viewModelScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { authorization.silentToken() }
                if (token == null) {
                    googleSyncState = googleSyncState.copy(
                        phase = SyncPhase.AuthenticationRequired,
                        message = "Google permission is required again."
                    )
                    return@launch
                }
                syncManager.sync(token, session.accountEmail)
                reloadFromDatabase()
            } catch (error: Exception) {
                syncFailed(error)
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

    private fun finishAuthorization(accessToken: String) {
        googleSyncState = googleSyncState.copy(
            authorizing = true,
            message = "Google access granted. Connecting Drive..."
        )
        viewModelScope.launch {
            try {
                syncManager.sync(accessToken)
                val email = requireNotNull(session.accountEmail)
                session.connect(email)
                SyncScheduler.ensurePeriodic(getApplication())
                reloadFromDatabase()
                googleSyncState = googleSyncState.copy(
                    accountName = email,
                    syncEnabled = true,
                    message = "Google Drive connected."
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                syncFailed(error)
            } finally {
                googleSyncState = googleSyncState.copy(authorizing = false)
            }
        }
    }

    private fun authorizationFailed(error: Throwable) {
        val message = when {
            error is TimeoutCancellationException ->
                "Google did not respond within 30 seconds. Check your network and Google Play services, then try again."
            error is ApiException && error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
                "Google OAuth error 10: check the Android OAuth package name and signing SHA-1 in Google Cloud."
            error is ApiException && error.statusCode == CommonStatusCodes.CANCELED ->
                "Google sign-in was canceled. Please try again."
            error is ApiException ->
                "Google authorization error ${error.statusCode}: ${error.message ?: CommonStatusCodes.getStatusCodeString(error.statusCode)}"
            else -> error.message ?: "Google authorization failed."
        }
        googleSyncState = googleSyncState.copy(
            authorizing = false,
            phase = SyncPhase.AuthenticationRequired,
            message = message
        )
    }

    private fun syncFailed(error: Throwable) {
        val snapshot = SyncSnapshot(
            phase = SyncPhase.Error,
            message = error.message ?: "Drive sync failed.",
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
        googleSyncState = googleSyncState.copy(
            pendingChanges = pending,
            message = if (session.connected) "Local changes queued for Drive." else "Saved offline on this device."
        )
        SyncStatusMonitor.update(
            SyncSnapshot(
                phase = if (session.connected) SyncPhase.Idle else SyncPhase.Offline,
                message = googleSyncState.message,
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

    private fun loadGoogleSyncState(): GoogleSyncState = GoogleSyncState(
        accountName = session.accountEmail,
        syncEnabled = session.connected,
        lastSyncedAt = session.lastSyncedAt?.let(::formatTime),
        pendingChanges = localStore.pendingCount(),
        conflicts = localStore.conflictCount(),
        message = if (session.connected) "Restored Google Drive connection." else "Connect a Google account."
    )

    private fun applySyncSnapshot(snapshot: SyncSnapshot) {
        googleSyncState = googleSyncState.copy(
            accountName = session.accountEmail,
            syncEnabled = session.connected,
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
