package com.fieldnote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.fieldnote.core.BuildProfile
import com.fieldnote.data.FeatureStatus
import com.fieldnote.data.FieldNoteRepository
import com.fieldnote.data.LocalFieldNoteRepository
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val DEFAULT_DUE_DAY = 2
private const val SYNC_PREFS = "field_note_google_sync"
private const val PREF_GOOGLE_ACCOUNT = "google_account_name"
private const val PREF_SYNC_FOLDER = "sync_folder_path"

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

data class MainUiState(
    val versionName: String = BuildProfile.appVersion,
    val targetDevices: List<String> = BuildProfile.targetDevices
)

data class GoogleSyncState(
    val accountName: String? = null,
    val syncEnabled: Boolean = false,
    val syncFolderPath: String? = null,
    val lastSyncedAt: String? = null,
    val message: String = "Google 계정을 연결해 주세요."
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: FieldNoteRepository = LocalFieldNoteRepository()
    private val prefs = application.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)

    val uiState = MainUiState()
    val featureStatuses: Flow<List<FeatureStatus>> = repository.featureStatuses

    var googleSyncState by mutableStateOf(loadGoogleSyncState())
        private set

    val todos = mutableStateListOf(
        TodoItem(1L, "노트 객체 모델 준비", null, false),
        TodoItem(2L, "달력 연결 할 일 항목 정의", DEFAULT_DUE_DAY, false),
        TodoItem(3L, "할 일 데이터와 노트 위치 분리", null, true)
    )

    val noteTodoPlacements = mutableStateListOf(
        NoteTodoPlacement(todoId = 1L, x = 80f, y = 84f),
        NoteTodoPlacement(todoId = 2L, x = 360f, y = 156f, fixed = true)
    )

    private var nextTodoId = 4L

    fun connectGoogleAccount(accountName: String) {
        val folder = configureSyncFolder(accountName)
        prefs.edit()
            .putString(PREF_GOOGLE_ACCOUNT, accountName)
            .putString(PREF_SYNC_FOLDER, folder.absolutePath)
            .apply()
        googleSyncState = GoogleSyncState(
            accountName = accountName,
            syncEnabled = true,
            syncFolderPath = folder.absolutePath,
            message = "Google 계정과 저장 폴더가 자동 설정되었습니다."
        )
    }

    fun disconnectGoogleAccount() {
        prefs.edit().remove(PREF_GOOGLE_ACCOUNT).remove(PREF_SYNC_FOLDER).apply()
        googleSyncState = GoogleSyncState(message = "Google 계정 연결이 해제되었습니다.")
    }

    fun autoConfigureSyncFolder() {
        val accountName = googleSyncState.accountName
        if (accountName == null) {
            googleSyncState = googleSyncState.copy(message = "먼저 Google 계정을 연결해 주세요.")
            return
        }

        val folder = configureSyncFolder(accountName)
        prefs.edit().putString(PREF_SYNC_FOLDER, folder.absolutePath).apply()
        googleSyncState = googleSyncState.copy(
            syncFolderPath = folder.absolutePath,
            message = "저장 폴더를 자동 설정했습니다."
        )
    }

    fun syncNow() {
        val accountName = googleSyncState.accountName
        if (accountName == null) {
            googleSyncState = googleSyncState.copy(message = "먼저 Google 계정을 연결해 주세요.")
            return
        }

        val folder = googleSyncState.syncFolderPath ?: configureSyncFolder(accountName).absolutePath
        prefs.edit().putString(PREF_SYNC_FOLDER, folder).apply()
        googleSyncState = googleSyncState.copy(
            syncEnabled = true,
            syncFolderPath = folder,
            lastSyncedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
            message = "${accountName} 기준 동기화 준비 데이터를 저장했습니다."
        )
    }

    fun addTodo(title: String, dueDay: Int? = null) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return

        val id = nextTodoId++
        todos.add(TodoItem(id = id, title = trimmedTitle, dueDay = dueDay))
        addTodoToNote(id)
    }

    fun addTodoToNote(todoId: Long) {
        if (noteTodoPlacements.any { it.todoId == todoId }) return
        noteTodoPlacements.add(NoteTodoPlacement(todoId = todoId, x = 120f, y = 220f + (noteTodoPlacements.size * 36f)))
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
        val normalizedDay = dueDay?.coerceIn(1, 30)
        updateTodo(todoId) { it.copy(dueDay = normalizedDay) }
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
        if (index < 0) return

        val current = noteTodoPlacements[index]
        noteTodoPlacements[index] = current.copy(fixed = !current.fixed)
    }

    private fun loadGoogleSyncState(): GoogleSyncState {
        val accountName = prefs.getString(PREF_GOOGLE_ACCOUNT, null)
        val folder = prefs.getString(PREF_SYNC_FOLDER, null)
        return if (accountName == null) {
            GoogleSyncState()
        } else {
            GoogleSyncState(
                accountName = accountName,
                syncEnabled = true,
                syncFolderPath = folder,
                message = "저장된 Google 계정을 불러왔습니다."
            )
        }
    }

    private fun configureSyncFolder(accountName: String): File {
        val safeAccount = accountName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val baseDir = getApplication<Application>().getExternalFilesDir("sync") ?: getApplication<Application>().filesDir
        val folder = File(baseDir, safeAccount)
        folder.mkdirs()
        return folder
    }

    private fun updateTodo(todoId: Long, transform: (TodoItem) -> TodoItem) {
        val index = todos.indexOfFirst { it.id == todoId }
        if (index >= 0) {
            todos[index] = transform(todos[index])
        }
    }
}
